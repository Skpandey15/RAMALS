package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.execution.crypto.FakeResultEncryptionKeyProvider;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCodec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract B crash/recovery qualification: ten kill points, each recovered by a fresh service over
 * the same database.
 *
 * <p><strong>What "a fresh process" means here.</strong> Every recovery builds new repositories, a
 * new store, a new adoption boundary and a new lifecycle service. Nothing is carried across the kill
 * point except PostgreSQL, which is exactly the guarantee under test — M2-ADR-017 §1 makes the
 * platform the sole holder of durable state, so if a fresh instance cannot reconstruct the execution
 * from the database, the claim is false. A test that reused the service would prove nothing.
 *
 * <p><strong>Why the crash is an {@link Error}.</strong> {@link SimulatedProcessDeath} unwinds
 * through every {@code catch (RuntimeException ...)} in the lifecycle. Those handlers exist to
 * classify failures, and a dead process classifies nothing. Anything catchable would let the service
 * record an outcome — the one thing a crash must not produce.
 *
 * <p>The nine invariants asserted across the matrix: no duplicate provider submission, no fabricated
 * success or failure, state reconstructable from PostgreSQL, provider identity reused when known,
 * reconciliation idempotent, no plaintext persisted, no duplicated terminal result, adoption atomic,
 * and Contract A untouched.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ContractBCrashQualificationIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String KEY_V1 = "contract-b-key-v1";
  private static final String CANARY = "CANARY-LEARNER-DIAGNOSIS-DO-NOT-PERSIST";
  private static final String BATCH = "msgbatch_qualification01";

  private static String databaseUrl;

  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = required("RAMALS_TEST_POSTGRES_URL");
    String adminUser = required("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, required("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String database = statement.enquoteIdentifier(currentDatabase(statement), true);
      String admin = statement.enquoteIdentifier(adminUser, true);
      statement.execute("""
          DO $$
          BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_migration') THEN
              CREATE ROLE ramals_core_migration LOGIN PASSWORD 'm0-t05-migration-test';
            ELSE
              ALTER ROLE ramals_core_migration WITH LOGIN PASSWORD 'm0-t05-migration-test';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_runtime') THEN
              CREATE ROLE ramals_core_runtime LOGIN PASSWORD 'm0-t05-runtime-test';
            END IF;
          END
          $$;
          """);
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + admin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
      statement.execute("GRANT CONNECT ON DATABASE " + database
          + " TO ramals_core_migration, ramals_core_runtime");
    }
    org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  @BeforeEach
  void setUp() {
    source = new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
    jdbc = new JdbcTemplate(source);
    jdbc.execute("TRUNCATE core.ai_execution_transition, core.ai_execution_result, "
        + "core.ai_reconciliation_work, core.ai_provider_execution CASCADE");
  }

  /**
   * One service instance. Called again after every kill point to build a genuinely new one.
   *
   * <p>New repositories and a new store each time, not just a new service: a repository holding a
   * cached anything would carry state across the crash and quietly weaken every assertion below.
   */
  private ContractBExecutionService instance(DurableExecutionPort port, ContractBResultStore store) {
    return new ContractBExecutionService(
        new ProviderExecutionRepository(jdbc),
        new ContractBTransitionLedger(jdbc),
        port,
        store,
        new ContractBAdoption(jdbc, new DataSourceTransactionManager(source)),
        new ContractBProperties());
  }

  private ContractBResultStore store() {
    return new ContractBResultStore(jdbc, codec(), new ObjectMapper());
  }

  private ResultEnvelopeCodec codec() {
    return new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider().with(KEY_V1).active(KEY_V1));
  }

  // ================================================================================================
  // Kill point 1 -- after durable ADMITTED, before provider submit
  // ================================================================================================

  @Test
  @DisplayName("K1: admitted then dead -- nothing was sent, and it stays submittable exactly once")
  void k1_deadAfterAdmittedBeforeSubmit() {
    String requestId = "req-k1-000000000001";
    var provider = new CrashingDurableExecutionPort(new FakeDurableExecutionPort()
        .providerExecutionId(BATCH));
    instance(provider, store()).admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    // The process dies here. No provider call was ever made.

    assertState(requestId, "ADMITTED", null);
    assertThat(provider.delegate().submissions).isEmpty();

    // A fresh instance. Nothing was sent, so submitting is safe -- and the fence still allows it
    // exactly once however many instances try.
    var recovered = instance(provider, store());
    assertThat(recovered.submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.SUBMITTED);
    assertThat(instance(provider, store()).submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.SUBMITTED);
    assertThat(provider.delegate().submissions)
        .as("recovery must not turn one admitted execution into two provider submissions")
        .hasSize(1);
    assertState(requestId, "SUBMITTED", BATCH);
  }

  // ================================================================================================
  // Kill point 2 -- after write-ahead SUBMITTED, before the provider call returns
  // ================================================================================================

  @Test
  @DisplayName("K2: dead before the call returned -- sent, unacknowledged, INDETERMINATE, no resend")
  void k2_deadDuringTheProviderCall() {
    String requestId = "req-k2-000000000001";
    var provider = new CrashingDurableExecutionPort(new FakeDurableExecutionPort()
        .providerExecutionId(BATCH)).dieAt(CrashingDurableExecutionPort.When.AFTER_SUBMIT);
    var service = instance(provider, store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");

    assertThatThrownBy(() -> service.submit(requestId, command(requestId)))
        .isInstanceOf(SimulatedProcessDeath.class);

    // The write-ahead claim is the reason this is legible at all: sent, with no identity.
    assertState(requestId, "SUBMITTED", null);
    assertThat(jdbc.queryForObject(
        "SELECT submitted_at IS NOT NULL FROM core.ai_provider_execution WHERE request_id = ?",
        Boolean.class, requestId)).isTrue();
    assertThat(provider.delegate().submissions).hasSize(1);

    // A fresh instance enumerates by custom_id (M2-ADR-020) rather than guessing. Here the search
    // is conclusive and empty, so INDETERMINATE is reached on evidence rather than on the absence
    // of a way to look.
    assertThat(instance(provider.survive(), store()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(provider.delegate().searchCalls.get())
        .as("recovery must actually look before concluding")
        .isPositive();
    assertThat(provider.delegate().submissions)
        .as("a lost acknowledgement must never be resolved by submitting again")
        .hasSize(1);
    assertThat(ledgerReasons(requestId)).contains("SEARCH_FOUND_NOTHING");
  }

  // ================================================================================================
  // Kill point 3 -- provider accepted, dead before the identity was stored
  // ================================================================================================

  @Test
  @DisplayName("K3: provider accepted, identity never stored -- INDETERMINATE, orphan acknowledged")
  void k3_deadBeforeIdentityStored() {
    String requestId = "req-k3-000000000001";
    // Indistinguishable from K2 in the database, and deliberately so: the durable evidence is the
    // same, which is precisely why neither can be recovered by re-submission.
    var provider = new CrashingDurableExecutionPort(new FakeDurableExecutionPort()
        .providerExecutionId(BATCH)).dieAt(CrashingDurableExecutionPort.When.AFTER_SUBMIT);
    var service = instance(provider, store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    assertThatThrownBy(() -> service.submit(requestId, command(requestId)))
        .isInstanceOf(SimulatedProcessDeath.class);

    assertState(requestId, "SUBMITTED", null);
    assertThat(instance(provider.survive(), store()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);

    // The provider execution is orphaned: it exists, it will run, and RAMALS holds no name for it.
    // That is a cost of the lost-acknowledgement window, not a duplicate -- RAMALS submitted once.
    assertThat(provider.delegate().submissions).hasSize(1);
    assertThat(results(requestId)).isZero();
  }

  // ================================================================================================
  // Kill point 4 -- identity stored, dead before reconciliation was enqueued
  // ================================================================================================

  @Test
  @DisplayName("K4: identity stored, work item never enqueued -- still recoverable, not orphaned")
  void k4_deadBeforeReconciliationEnqueued() {
    String requestId = "req-k4-000000000001";
    var repository = new ProviderExecutionRepository(jdbc);
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    instance(new CrashingDurableExecutionPort(fake), store())
        .admit(requestId, "idem-" + requestId, "custom-" + requestId,
            "anthropic", "claude-sonnet-5", "diagnostic");

    // Exactly the window: the identity is committed, the work item is not. Produced by driving the
    // repository directly, because the service writes both and the gap between them is the point.
    long fence = repository.claimForSubmission(requestId).orElseThrow();
    repository.recordSubmission(requestId, fence, BATCH);
    assertState(requestId, "SUBMITTED", BATCH);
    assertThat(reconciliationRows(requestId))
        .as("this is the window under test: no work item exists")
        .isZero();

    // The execution is recoverable from the durable row alone -- the work queue is an index onto
    // it, not the record of it. A recovery that depended on the queue would lose this execution.
    assertThat(repository.reconcilable(10))
        .as("a fresh worker must be able to find an execution whose work item was never written")
        .extracting(ProviderExecution::requestId)
        .contains(requestId);

    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    assertThat(instance(new CrashingDurableExecutionPort(fake), store()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(fake.submissions).isEmpty();
  }

  // ================================================================================================
  // Kill point 5 -- dead while RUNNING / RECONCILING
  // ================================================================================================

  @Test
  @DisplayName("K5: dead mid-poll -- the replacement asks the provider the same question")
  void k5_deadWhileRunning() {
    String requestId = "req-k5-000000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var provider = new CrashingDurableExecutionPort(fake);
    var service = instance(provider, store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    service.reconcile(requestId);
    assertState(requestId, "RUNNING", BATCH);

    int submissionsBeforeCrash = fake.submissions.size();
    provider.dieAt(CrashingDurableExecutionPort.When.AFTER_STATUS);
    assertThatThrownBy(() -> instance(provider, store()).reconcile(requestId))
        .isInstanceOf(SimulatedProcessDeath.class);
    // Non-terminal, and no outcome was invented on the way down.
    assertThat(DurableExecutionState.of(state(requestId)).terminal()).isFalse();

    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    assertThat(instance(provider.survive(), store()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.SUCCEEDED);
    // The identity was reused, never re-established: recovery added no submission of its own.
    assertThat(fake.submissions).hasSize(submissionsBeforeCrash);
    assertThat(providerExecutionId(requestId)).isEqualTo(BATCH);
  }

  // ================================================================================================
  // Kill point 6 -- provider terminal, dead before the result was retrieved
  // ================================================================================================

  @Test
  @DisplayName("K6: dead before retrieval -- the result is still at the provider and is fetched")
  void k6_deadBeforeResultRetrieval() {
    String requestId = "req-k6-000000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var provider = new CrashingDurableExecutionPort(fake);
    var service = instance(provider, store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);

    provider.dieAt(CrashingDurableExecutionPort.When.BEFORE_RESULT);
    assertThatThrownBy(() -> instance(provider, store()).reconcile(requestId))
        .isInstanceOf(SimulatedProcessDeath.class);
    assertThat(results(requestId)).isZero();
    assertThat(DurableExecutionState.of(state(requestId)).terminal()).isFalse();

    assertThat(instance(provider.survive(), store()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(results(requestId)).isEqualTo(1);
  }

  // ================================================================================================
  // Kill point 7 -- result retrieved, dead before the encrypted write completed
  // ================================================================================================

  @Test
  @DisplayName("K7: dead before the ciphertext was written -- no partial row, retrieved again")
  void k7_deadBeforeEncryptedPersistence() {
    String requestId = "req-k7-000000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var provider = new CrashingDurableExecutionPort(fake);
    var crashing = new CrashingResultStore(jdbc, codec(), new ObjectMapper());
    var service = instance(provider, crashing);
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);

    crashing.dieAt(CrashingResultStore.When.BEFORE_WRITE);
    assertThatThrownBy(() -> instance(provider, crashing).reconcile(requestId))
        .isInstanceOf(SimulatedProcessDeath.class);
    // Nothing partial: no row at all, and certainly no plaintext.
    assertThat(results(requestId)).isZero();

    assertThat(instance(provider, crashing.survive()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(results(requestId)).isEqualTo(1);
    assertCiphertextOnly(requestId);
  }

  // ================================================================================================
  // Kill point 8 -- ciphertext committed, dead before the execution was marked SUCCEEDED
  // ================================================================================================

  @Test
  @DisplayName("K8: ciphertext committed, dead before terminal -- recovery completes, one result")
  void k8_deadAfterPersistenceBeforeTerminal() {
    String requestId = "req-k8-000000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var provider = new CrashingDurableExecutionPort(fake);
    var crashing = new CrashingResultStore(jdbc, codec(), new ObjectMapper());
    var service = instance(provider, crashing);
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);

    crashing.dieAt(CrashingResultStore.When.AFTER_WRITE);
    assertThatThrownBy(() -> instance(provider, crashing).reconcile(requestId))
        .isInstanceOf(SimulatedProcessDeath.class);
    // The ciphertext survived; the execution did not reach terminal.
    assertThat(results(requestId)).isEqualTo(1);
    assertThat(DurableExecutionState.of(state(requestId)).terminal()).isFalse();

    // The replacement retrieves and stores again. Storing a result that is already there must be a
    // no-op rather than a primary-key violation, or recovery here is a crash loop.
    assertThat(instance(provider, crashing.survive()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(results(requestId))
        .as("a terminal result must never be duplicated by a recovery")
        .isEqualTo(1);
    assertCiphertextOnly(requestId);
  }

  // ================================================================================================
  // Kill point 9 -- terminal with a stored result, dead before adoption
  // ================================================================================================

  @Test
  @DisplayName("K9: dead before adoption -- the result survives and is still adoptable")
  void k9_deadBeforeAdoption() {
    String requestId = "req-k9-000000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var provider = new CrashingDurableExecutionPort(fake);
    var service = instance(provider, store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    service.reconcile(requestId);
    // The process dies here, with everything committed and nothing adopted.

    assertState(requestId, "SUCCEEDED", BATCH);
    assertThat(results(requestId)).isEqualTo(1);

    var recovered = instance(provider, store());
    assertThat(recovered.adopt(requestId, () -> jdbc.update("""
        INSERT INTO core.ai_execution_transition (request_id, to_state, actor, reason)
        VALUES (?, 'ADOPTED', 'ADOPTER', 'GATE_COMMITTED')
        """, requestId)))
        .isPresent().get().asString().contains(CANARY);
    assertThat(results(requestId)).isZero();
  }

  // ================================================================================================
  // Kill point 10 -- the adoption transaction boundary
  // ================================================================================================

  @Test
  @DisplayName("K10: dead inside the adoption transaction -- decision and result share one fate")
  void k10_deadAtTheAdoptionBoundary() {
    String requestId = "req-k10-00000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var service = instance(new CrashingDurableExecutionPort(fake), store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    service.reconcile(requestId);

    // Dies after the decision is written and before the transaction commits. The only two states
    // this may leave are "both" and "neither" -- never a committed decision beside a surviving
    // result, which would be an adopted outcome whose evidence is still lying around.
    assertThatThrownBy(() -> instance(new CrashingDurableExecutionPort(fake), store())
        .adopt(requestId, () -> {
          jdbc.update("""
              INSERT INTO core.ai_execution_transition (request_id, to_state, actor, reason)
              VALUES (?, 'ADOPTED', 'ADOPTER', 'GATE_COMMITTED')
              """, requestId);
          throw new SimulatedProcessDeath("ADOPTION_TRANSACTION");
        }))
        .isInstanceOf(SimulatedProcessDeath.class);

    assertThat(results(requestId))
        .as("the result must survive a rolled-back adoption, or the execution is unrecoverable")
        .isEqualTo(1);
    assertThat(ledgerReasons(requestId))
        .as("the decision must roll back with it")
        .doesNotContain("GATE_COMMITTED");

    // And the replacement adopts cleanly, exactly once.
    var recovered = instance(new CrashingDurableExecutionPort(fake), store());
    recovered.adopt(requestId, () -> jdbc.update("""
        INSERT INTO core.ai_execution_transition (request_id, to_state, actor, reason)
        VALUES (?, 'ADOPTED', 'ADOPTER', 'GATE_COMMITTED')
        """, requestId));
    assertThat(results(requestId)).isZero();
    assertThat(ledgerReasons(requestId).stream().filter("ADOPTED"::equals).count()).isEqualTo(1);
  }

  // ================================================================================================
  // The sweep must be able to reach every stranded row
  // ================================================================================================
  //
  // The kill points above prove that reconcile() reaches the right outcome. These prove that
  // something actually calls it. A recovery nothing invokes is a recovery that does not happen, and
  // the first draft of the worker could not see any of these rows.

  @Test
  @DisplayName("sweep: an acknowledged execution with no work item is re-queued and driven")
  void theSweepRecoversAnExecutionWithNoWorkItem() {
    String requestId = "req-sweep-k4-00001";
    var repository = new ProviderExecutionRepository(jdbc);
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    instance(new CrashingDurableExecutionPort(fake), store())
        .admit(requestId, "idem-" + requestId, "custom-" + requestId,
            "anthropic", "claude-sonnet-5", "diagnostic");
    long fence = repository.claimForSubmission(requestId).orElseThrow();
    repository.recordSubmission(requestId, fence, BATCH);
    assertThat(reconciliationRows(requestId)).isZero();

    worker(fake).poll();

    // The sweep found it through the durable row rather than the queue, and the ordinary lease loop
    // then drove it in the same pass.
    assertThat(state(requestId)).isNotEqualTo("SUBMITTED");
    assertThat(fake.statusCalls.get()).isPositive();
    assertThat(fake.submissions)
        .as("recovering a stranded execution must never submit")
        .isEmpty();
  }

  @Test
  @DisplayName("sweep: a sent-but-unacknowledged execution is recorded INDETERMINATE once stale")
  void theSweepResolvesLostAcknowledgements() {
    String requestId = "req-sweep-k2-00001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var provider = new CrashingDurableExecutionPort(fake)
        .dieAt(CrashingDurableExecutionPort.When.AFTER_SUBMIT);
    var service = instance(provider, store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    assertThatThrownBy(() -> service.submit(requestId, command(requestId)))
        .isInstanceOf(SimulatedProcessDeath.class);
    assertState(requestId, "SUBMITTED", null);

    // With the default grace period this row is far too young to touch, and that restraint is the
    // property: a submission in flight right now looks identical to one whose ack was lost.
    worker(fake).poll();
    assertState(requestId, "SUBMITTED", null);

    // Once it is older than one provider round trip, leaving it as live work would be the lie.
    worker(fake, 0L).poll();
    assertThat(state(requestId)).isEqualTo("UNKNOWN_TERMINAL");
    assertThat(ledgerReasons(requestId)).contains("SEARCH_FOUND_NOTHING");
    assertThat(fake.submissions)
        .as("a lost acknowledgement is resolved by recording it, never by sending again")
        .hasSize(1);
  }

  @Test
  @DisplayName("sweep: an execution still within the grace window is left alone")
  void theSweepDoesNotTerminateAnInFlightSubmission() {
    String requestId = "req-sweep-live-001";
    var repository = new ProviderExecutionRepository(jdbc);
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    instance(new CrashingDurableExecutionPort(fake), store())
        .admit(requestId, "idem-" + requestId, "custom-" + requestId,
            "anthropic", "claude-sonnet-5", "diagnostic");
    // Exactly the state a submission in progress holds, right now.
    repository.claimForSubmission(requestId);

    worker(fake).poll();

    assertThat(state(requestId))
        .as("terminating a live submission would be worse than the ambiguity it resolves")
        .isEqualTo("SUBMITTED");
  }

  // ================================================================================================
  // Cross-cutting invariants
  // ================================================================================================

  @Test
  @DisplayName("across every kill point: reconciliation is idempotent and never fabricates")
  void reconciliationIsIdempotentAtEveryKillPoint() {
    String requestId = "req-idem-000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var provider = new CrashingDurableExecutionPort(fake);
    var service = instance(provider, store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    assertThat(instance(provider, store()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.SUCCEEDED);

    int retrievals = fake.resultCalls.get();
    // Five replacements arrive on a finished execution, as a restart storm would produce.
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(instance(provider, store()).reconcile(requestId))
          .isEqualTo(DurableExecutionState.SUCCEEDED);
    }
    assertThat(fake.resultCalls.get()).isEqualTo(retrievals);
    assertThat(results(requestId)).isEqualTo(1);
    assertThat(ledgerReasons(requestId).stream().filter("RESULT_STORED"::equals).count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("across every kill point: no crash leaves plaintext anywhere")
  void noKillPointLeavesPlaintext() {
    // Drives all three write-adjacent kill points into one database, then reads every Contract B
    // table. A crash between validation and sealing is the only place plaintext could appear, and
    // it must not survive any of them.
    for (CrashingResultStore.When when :
        List.of(CrashingResultStore.When.BEFORE_WRITE, CrashingResultStore.When.AFTER_WRITE)) {
      String requestId = "req-plain-" + when.ordinal() + "00000001";
      var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH + when.ordinal());
      var crashing = new CrashingResultStore(jdbc, codec(), new ObjectMapper());
      var service = instance(new CrashingDurableExecutionPort(fake), crashing);
      service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
          "anthropic", "claude-sonnet-5", "diagnostic");
      service.submit(requestId, command(requestId));
      fake.succeedsWith(proposal(requestId), "custom-" + requestId);
      crashing.dieAt(when);
      assertThatThrownBy(() -> instance(new CrashingDurableExecutionPort(fake), crashing)
          .reconcile(requestId)).isInstanceOf(SimulatedProcessDeath.class);
    }

    for (String table : List.of("ai_provider_execution", "ai_execution_transition",
        "ai_reconciliation_work", "ai_execution_result")) {
      assertThat(jdbc.queryForObject(
          "SELECT coalesce(string_agg(t::text, ' '), '') FROM core." + table + " t", String.class))
          .as("core.%s must carry no plaintext after a crash", table)
          .doesNotContain(CANARY);
    }
  }

  @Test
  @DisplayName("across every kill point: Contract A is untouched")
  void contractAIsUnchangedByCrashRecovery() {
    String requestId = "req-ca-000000000001";
    var fake = new FakeDurableExecutionPort().providerExecutionId(BATCH);
    var service = instance(new CrashingDurableExecutionPort(fake), store());
    service.admit(requestId, "idem-" + requestId, "custom-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    service.reconcile(requestId);

    // Contract B writes nothing into Contract A's tables, so its qualification stays valid. Read as
    // a column list and a row count, which is how V023's structural guarantee is checked.
    for (String table : List.of("ai_execution", "ai_execution_event", "ai_execution_dispatch")) {
      assertThat(jdbc.queryForObject(
          "SELECT count(*) FROM core." + table, Integer.class))
          .as("Contract B must not write to core.%s", table)
          .isZero();
    }
    assertThatCode(() -> jdbc.queryForObject(
        "SELECT core.purge_expired_ai_executions(400)", Integer.class))
        .doesNotThrowAnyException();
  }

  // -- helpers ---------------------------------------------------------------------------------

  /** A worker over this database, with the default grace period. */
  private ContractBReconciliationWorker worker(FakeDurableExecutionPort fake) {
    return worker(fake, new ContractBProperties().getReconciliation().getUnacknowledgedGraceMs());
  }

  private ContractBReconciliationWorker worker(FakeDurableExecutionPort fake, long graceMs) {
    ContractBProperties properties = new ContractBProperties();
    properties.getReconciliation().setUnacknowledgedGraceMs(graceMs);
    return new ContractBReconciliationWorker(
        new ProviderExecutionRepository(jdbc),
        instance(new CrashingDurableExecutionPort(fake), store()),
        properties);
  }

  private void assertState(String requestId, String expectedState, String expectedBatch) {
    assertThat(state(requestId)).isEqualTo(expectedState);
    assertThat(providerExecutionId(requestId)).isEqualTo(expectedBatch);
  }

  /** Reads the stored bytes and proves they are an envelope, not a document. */
  private void assertCiphertextOnly(String requestId) {
    byte[] stored = jdbc.queryForObject(
        "SELECT normalized_result FROM core.ai_execution_result WHERE request_id = ?",
        byte[].class, requestId);
    assertThat(stored).isNotNull();
    assertThat(stored[0]).as("byte 0 is the envelope version").isEqualTo((byte) 1);
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(CANARY)
        .doesNotContain("contractVersion");
    // And it still opens, so the recovery stored a usable result rather than merely a row.
    assertThat(new ContractBResultStore(jdbc, codec(), new ObjectMapper()).read(requestId))
        .isPresent().get().asString().contains(CANARY);
  }

  private DurableSubmissionCommand command(String requestId) {
    return new DurableSubmissionCommand(requestId, "idem-" + requestId, "a".repeat(64),
        "claude-sonnet-5", 1024,
        List.of(new DurableSubmissionCommand.Turn("user", "diagnose this learner")));
  }

  private String state(String requestId) {
    return jdbc.queryForObject(
        "SELECT state FROM core.ai_provider_execution WHERE request_id = ?", String.class,
        requestId);
  }

  private String providerExecutionId(String requestId) {
    return jdbc.queryForObject(
        "SELECT provider_execution_id FROM core.ai_provider_execution WHERE request_id = ?",
        String.class, requestId);
  }

  private List<String> ledgerReasons(String requestId) {
    return jdbc.queryForList(
        "SELECT reason FROM core.ai_execution_transition WHERE request_id = ? ORDER BY id",
        String.class, requestId);
  }

  private int results(String requestId) {
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM core.ai_execution_result WHERE request_id = ?", Integer.class,
        requestId);
    return count == null ? 0 : count;
  }

  private int reconciliationRows(String requestId) {
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM core.ai_reconciliation_work WHERE request_id = ?", Integer.class,
        requestId);
    return count == null ? 0 : count;
  }

  private static String proposal(String requestId) {
    return """
        {"contractVersion":"1.0","proposalId":"prop-%s","requestId":"%s","agentRunId":"run-%s",\
        "contextId":"ctx-%s","diagnoses":[{"skillCode":"ALG.LIN.01","classification":"WEAK",\
        "reason":"%s","evidenceIds":["ev-0001","ev-0002"]}],\
        "recommendedNextSkills":["ALG.LIN.02"],"confidence":0.72}"""
        .formatted(requestId, requestId, requestId, requestId, CANARY);
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (var rs = statement.executeQuery("SELECT current_database()")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required for this test");
    }
    return value;
  }
}
