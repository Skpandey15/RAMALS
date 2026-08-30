package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.execution.crypto.FakeResultEncryptionKeyProvider;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCodec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
 * The Contract B lifecycle against real PostgreSQL:
 * {@code ADMITTED → SUBMITTED → RUNNING/RECONCILING → SUCCEEDED | FAILED | UNKNOWN_TERMINAL}.
 *
 * <p>Every test asserts on the durable row and the transition ledger rather than on a return value,
 * because the return value is the thing that does not survive a process death. What a replacement
 * worker can see is what the lifecycle actually guarantees.
 *
 * <p>Restart is simulated by discarding the service and building a new one over the same database —
 * which is exactly what a restart is, given the AI plane holds no state and the platform holds it
 * all. A test that kept the same instance would prove nothing about recovery.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ContractBLifecycleIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String KEY_V1 = "contract-b-key-v1";
  private static final String CANARY = "CANARY-LEARNER-DIAGNOSIS-DO-NOT-PERSIST";

  private static String databaseUrl;

  private JdbcTemplate jdbc;
  private ProviderExecutionRepository executions;
  private ContractBTransitionLedger ledger;
  private ContractBResultStore results;
  private ContractBAdoption adoption;

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
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
      statement.execute("GRANT CONNECT ON DATABASE " + database
          + " TO ramals_core_migration, ramals_core_runtime");
    }
    org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
    jdbc = new JdbcTemplate(source);
    jdbc.execute("TRUNCATE core.ai_execution_transition, core.ai_execution_result, "
        + "core.ai_reconciliation_work, core.ai_provider_execution CASCADE");

    executions = new ProviderExecutionRepository(jdbc);
    ledger = new ContractBTransitionLedger(jdbc);
    results = new ContractBResultStore(jdbc,
        new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider().with(KEY_V1).active(KEY_V1)),
        new ObjectMapper());
    adoption = new ContractBAdoption(jdbc, new DataSourceTransactionManager(source));
  }

  /** A fresh service over the same database. This is what a restart looks like from here. */
  private ContractBExecutionService serviceWith(FakeDurableExecutionPort port) {
    return new ContractBExecutionService(
        executions, ledger, port, results, adoption, new ContractBProperties());
  }

  // ================================================================================================
  // 1 -- the ordinary path
  // ================================================================================================

  @Test
  @DisplayName("success: admitted, submitted, polled, retrieved, stored, terminal")
  void normalSuccess() {
    String requestId = "req-ok-000000000001";
    var port = new FakeDurableExecutionPort().providerExecutionId("msgbatch_ok0000000001");
    var service = serviceWith(port);

    assertThat(service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic")).isTrue();
    assertThat(state(requestId)).isEqualTo("ADMITTED");

    assertThat(service.submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.SUBMITTED);
    // The identity is durable the moment the provider acknowledges. Everything downstream depends
    // on this row surviving, so it is asserted directly rather than through a return value.
    assertThat(providerExecutionId(requestId)).isEqualTo("msgbatch_ok0000000001");

    // Still working: stays non-terminal and comes back.
    assertThat(service.reconcile(requestId)).isEqualTo(DurableExecutionState.RUNNING);

    port.succeedsWith(proposal(requestId), "idem-" + requestId);
    assertThat(service.reconcile(requestId)).isEqualTo(DurableExecutionState.SUCCEEDED);

    assertThat(state(requestId)).isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForObject(
        "SELECT terminal_at IS NOT NULL FROM core.ai_provider_execution WHERE request_id = ?",
        Boolean.class, requestId)).isTrue();
    assertThat(results.exists(requestId)).isTrue();
    assertThat(ledgerReasons(requestId))
        .contains("ADMITTED", "PROVIDER_ACCEPTED", "RECONCILE_STARTED", "RESULT_STORED");
    // Terminal means the work item is gone, so no worker keeps polling a finished execution.
    assertThat(reconciliationRows(requestId)).isZero();
  }

  // ================================================================================================
  // 2 -- provider failure
  // ================================================================================================

  @Test
  @DisplayName("provider failure: a definite refusal is FAILED, and nothing is stored")
  void providerRefusalIsFailed() {
    String requestId = "req-refused-000001";
    var port = new FakeDurableExecutionPort().refusedSubmit();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    assertThat(service.submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.FAILED);
    // A classified refusal is a definite fact: the far side chose a status, so nothing was accepted
    // and nothing is running. FAILED is true here in a way it would not be after a timeout, and in
    // a way it would not be after an exception nobody classified.
    assertThat(state(requestId)).isEqualTo("FAILED");
    assertThat(providerExecutionId(requestId)).isNull();
    assertThat(results.exists(requestId)).isFalse();
    assertThat(ledgerReasons(requestId)).contains("SUBMIT_REFUSED");
  }

  @Test
  @DisplayName("only a classified refusal becomes FAILED: an unclassified failure never does")
  void anUnclassifiedFailureIsNeverFailed() {
    String requestId = "req-unclass-000001";
    var port = new FakeDurableExecutionPort().unclassifiedSubmitFailure();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    // The failure carries no diagnosis, so it cannot prove the provider created nothing. Recording
    // FAILED here would assert knowledge the code does not have, and would hide a live execution.
    assertThat(service.submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(state(requestId))
        .as("an undiagnosed failure must never be recorded as a definite failure")
        .isNotEqualTo("FAILED")
        .isEqualTo("UNKNOWN_TERMINAL");
    assertThat(ledgerReasons(requestId)).contains("SUBMIT_UNCLASSIFIED");

    // And it is terminal, so nothing resubmits it -- the outcome that would duplicate live work.
    assertThat(serviceWith(port).submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(serviceWith(port).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(port.submissions)
        .as("exactly one provider call, whatever the failure was")
        .hasSize(1);
  }

  @Test
  @DisplayName("the three submission outcomes are distinct, and only one may be FAILED")
  void submissionOutcomesAreClassifiedSeparately() {
    // Read as a table: same call site, three exception classes, three durable outcomes. The middle
    // and last rows agree, and that agreement is the fail-closed rule rather than a coincidence.
    record Case(String requestId, FakeDurableExecutionPort port, DurableExecutionState expected) {}
    List<Case> cases = List.of(
        new Case("req-cls-refused-01", new FakeDurableExecutionPort().refusedSubmit(),
            DurableExecutionState.FAILED),
        new Case("req-cls-ambig-0001", new FakeDurableExecutionPort().ambiguousSubmit(),
            DurableExecutionState.UNKNOWN_TERMINAL),
        new Case("req-cls-unclass-01", new FakeDurableExecutionPort().unclassifiedSubmitFailure(),
            DurableExecutionState.UNKNOWN_TERMINAL));

    for (Case scenario : cases) {
      var service = serviceWith(scenario.port());
      service.admit(scenario.requestId(), "idem-" + scenario.requestId(), "anthropic",
          "claude-sonnet-5", "diagnostic");

      assertThat(service.submit(scenario.requestId(), command(scenario.requestId())))
          .as("%s", scenario.requestId())
          .isEqualTo(scenario.expected());
      assertThat(state(scenario.requestId())).isEqualTo(scenario.expected().name());
      assertThat(scenario.port().submissions)
          .as("%s must reach the provider exactly once", scenario.requestId())
          .hasSize(1);
      // Nothing was stored on any of the three paths.
      assertThat(results.exists(scenario.requestId())).isFalse();
    }
  }

  @Test
  @DisplayName("transport ambiguity is INDETERMINATE and submits exactly once")
  void transportAmbiguityIsIndeterminate() {
    String requestId = "req-transport-0001";
    var port = new FakeDurableExecutionPort().ambiguousSubmit();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    assertThat(service.submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(state(requestId)).isNotEqualTo("FAILED");
    assertThat(port.submissions).hasSize(1);
  }

  @Test
  @DisplayName("provider failure: a terminal error record is FAILED and stores no result")
  void providerErrorRecordIsFailed() {
    String requestId = "req-errored-000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));

    port.recordOutcome("errored", "idem-" + requestId);
    assertThat(service.reconcile(requestId)).isEqualTo(DurableExecutionState.FAILED);
    assertThat(results.exists(requestId)).isFalse();
  }

  @Test
  @DisplayName("provider failure: a result that fails validation is FAILED, and never stored")
  void invalidResultIsFailedAndNeverStored() {
    String requestId = "req-invalid-000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));

    // A raw provider body carrying a reasoning trace. Validation happens before encryption, so this
    // never reaches a cipher and never reaches the column.
    port.succeedsWith("{\"thinking\":\"" + CANARY + "\",\"diagnoses\":[]}", "idem-" + requestId);
    assertThat(service.reconcile(requestId)).isEqualTo(DurableExecutionState.FAILED);
    assertThat(results.exists(requestId)).isFalse();
    assertThat(ledgerReasons(requestId)).contains("RESULT_SCHEMA_INVALID");
  }

  // ================================================================================================
  // 3 -- restart recovery
  // ================================================================================================

  @Test
  @DisplayName("restart: a new process finishes an execution the dead one submitted")
  void restartRecoversAnInFlightExecution() {
    String requestId = "req-restart-000001";
    var beforeCrash = new FakeDurableExecutionPort().providerExecutionId("msgbatch_restart00001");

    // Submitting an unadmitted request reaches no provider. The durable row is the authority on
    // what exists, and there is nothing here to own.
    assertThatThrownBy(() -> serviceWith(beforeCrash).submit(requestId, command(requestId)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(beforeCrash.submissions).isEmpty();

    var service = serviceWith(beforeCrash);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    assertThat(state(requestId)).isEqualTo("SUBMITTED");

    // The process dies here. Everything it knew is gone; the row and the ledger are not.
    var afterRestart = new FakeDurableExecutionPort()
        .providerExecutionId("msgbatch_restart00001");
    afterRestart.succeedsWith(proposal(requestId), "idem-" + requestId);
    var recovered = serviceWith(afterRestart);

    assertThat(recovered.reconcile(requestId)).isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(results.exists(requestId)).isTrue();
    // The replacement never submitted. It asked about the execution the dead process started.
    assertThat(afterRestart.submissions).isEmpty();
    assertThat(afterRestart.statusCalls.get()).isPositive();
  }

  @Test
  @DisplayName("restart: an unreachable provider leaves the execution recoverable, not failed")
  void anUnreachableProviderIsNotAnOutcome() {
    String requestId = "req-unreach-000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));

    var offline = new FakeDurableExecutionPort().statusUnavailable();
    // The provider being unreachable says nothing about the execution. Declaring it failed would
    // discard work that is very likely still running.
    assertThat(serviceWith(offline).reconcile(requestId))
        .isEqualTo(DurableExecutionState.RECONCILING);
    assertThat(DurableExecutionState.of(state(requestId)).terminal()).isFalse();
  }

  @Test
  @DisplayName("restart mid-submit: sent but unacknowledged becomes INDETERMINATE, never a resubmit")
  void aCrashDuringTheProviderCallIsIndeterminate() {
    String requestId = "req-midsubmit-0001";
    var service = serviceWith(new FakeDurableExecutionPort());
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    // The exact state a process death during the provider call leaves behind, produced by the
    // write-ahead claim rather than simulated: handed over, no identity, nothing to poll.
    executions.claimForSubmission(requestId);
    assertThat(state(requestId)).isEqualTo("SUBMITTED");
    assertThat(providerExecutionId(requestId)).isNull();
    assertThat(jdbc.queryForObject(
        "SELECT submitted_at IS NOT NULL FROM core.ai_provider_execution WHERE request_id = ?",
        Boolean.class, requestId))
        .as("the attempt must be durable before the call, or this looks freshly submittable")
        .isTrue();

    // A replacement worker arrives. It must not submit: it cannot tell this from an execution the
    // provider accepted and is running right now. It enumerates by custom_id instead (M2-ADR-020),
    // and here that search is conclusive and empty.
    var afterRestart = new FakeDurableExecutionPort();
    assertThat(serviceWith(afterRestart).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(afterRestart.submissions).isEmpty();
    assertThat(afterRestart.searchCalls.get())
        .as("the orphan is searched for, not guessed at")
        .isPositive();
    assertThat(afterRestart.statusCalls.get())
        .as("there is still no identity to poll")
        .isZero();
    assertThat(ledgerReasons(requestId)).contains("SEARCH_FOUND_NOTHING");
  }

  // ================================================================================================
  // 4 -- exactly one submission, and duplicate reconciliation
  // ================================================================================================

  @Test
  @DisplayName("exactly one provider submission per owned execution, however often submit is called")
  void neverSubmitsTwice() {
    String requestId = "req-once-0000000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    service.submit(requestId, command(requestId));
    // A second caller, a retried message, a duplicated scheduler tick -- all the same to the row.
    service.submit(requestId, command(requestId));
    serviceWith(port).submit(requestId, command(requestId));

    assertThat(port.submissions)
        .as("a second submission would be a duplicate provider execution")
        .hasSize(1);
    assertThat(state(requestId)).isEqualTo("SUBMITTED");
  }

  @Test
  @DisplayName("duplicate reconciliation is idempotent and writes no second terminal entry")
  void duplicateReconciliationIsIdempotent() {
    String requestId = "req-dupe-0000000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    port.succeedsWith(proposal(requestId), "idem-" + requestId);

    assertThat(service.reconcile(requestId)).isEqualTo(DurableExecutionState.SUCCEEDED);
    int resultCallsAfterFirst = port.resultCalls.get();

    // Two more workers arrive on an execution that is already finished.
    assertThat(service.reconcile(requestId)).isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(serviceWith(port).reconcile(requestId)).isEqualTo(DurableExecutionState.SUCCEEDED);

    // A terminal execution is not re-driven: no second retrieval, no second ledger claim.
    assertThat(port.resultCalls.get()).isEqualTo(resultCallsAfterFirst);
    assertThat(ledgerReasons(requestId).stream().filter("RESULT_STORED"::equals).count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("two provider executions can never claim the same identity")
  void aProviderExecutionIdentityIsUnique() {
    var port = new FakeDurableExecutionPort().providerExecutionId("msgbatch_shared000001");
    var service = serviceWith(port);
    service.admit("req-first-00000001", "idem-first", "anthropic", "claude-sonnet-5", "diagnostic");
    service.admit("req-second-0000001", "idem-second", "anthropic", "claude-sonnet-5", "diagnostic");

    service.submit("req-first-00000001", command("req-first-00000001"));
    // The same batch id coming back for a second request is exactly what a duplicate looks like.
    // V037's unique index refuses it rather than letting two requests share one execution.
    assertThatThrownBy(() -> service.submit("req-second-0000001", command("req-second-0000001")))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  // ================================================================================================
  // 5 -- ambiguous submission
  // ================================================================================================

  @Test
  @DisplayName("ambiguous submit is INDETERMINATE, and is never resubmitted")
  void ambiguousSubmitFailsClosed() {
    String requestId = "req-ambiguous-00001";
    var port = new FakeDurableExecutionPort().ambiguousSubmit();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    assertThat(service.submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(state(requestId)).isEqualTo("UNKNOWN_TERMINAL");
    assertThat(providerExecutionId(requestId)).isNull();
    assertThat(ledgerReasons(requestId)).contains("SUBMIT_AMBIGUOUS");

    // Terminal, and it stays terminal. A later worker must not resolve the ambiguity by trying
    // again: the provider may be running this work, and this provider offers no replay-safe
    // admission that would make a retry safe.
    assertThat(serviceWith(port).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(port.submissions).hasSize(1);
  }

  @Test
  @DisplayName("an acknowledgement without an execution identity is INDETERMINATE, not success")
  void acknowledgementWithoutIdentityIsIndeterminate() {
    String requestId = "req-noident-000001";
    var port = new FakeDurableExecutionPort().acknowledgeWithoutIdentity();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    // A 2xx that leaves RAMALS unable to poll is the same position as never having heard back.
    assertThat(service.submit(requestId, command(requestId)))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(ledgerReasons(requestId)).contains("SUBMIT_ACK_UNUSABLE");
  }

  @Test
  @DisplayName("a provider-expired execution is INDETERMINATE, never a failure")
  void expiryIsIndeterminate() {
    String requestId = "req-expired-000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));

    port.state("EXPIRED", "expired");
    assertThat(service.reconcile(requestId)).isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(DurableExecutionState.UNKNOWN_TERMINAL.terminalStatus()).isEqualTo("INDETERMINATE");
  }

  // ================================================================================================
  // 6 -- result encryption
  // ================================================================================================

  @Test
  @DisplayName("only ciphertext is persisted, and it round-trips through the codec")
  void onlyCiphertextIsPersisted() {
    String requestId = "req-crypto-000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    port.succeedsWith(proposal(requestId), "idem-" + requestId);
    service.reconcile(requestId);

    byte[] stored = jdbc.queryForObject(
        "SELECT normalized_result FROM core.ai_execution_result WHERE request_id = ?",
        byte[].class, requestId);
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(CANARY)
        .doesNotContain("diagnoses");
    assertThat(stored[0]).isEqualTo((byte) 1);
    assertThat(jdbc.queryForObject(
        "SELECT encryption_key_id FROM core.ai_execution_result WHERE request_id = ?",
        String.class, requestId)).isEqualTo(KEY_V1);
    // And it is the learner's document, recoverable only through the codec.
    assertThat(results.read(requestId)).isPresent().get().asString().contains(CANARY);
  }

  @Test
  @DisplayName("no surviving Contract B table carries the provider's output")
  void noOtherTableCarriesTheResult() {
    String requestId = "req-nocarry-000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    port.succeedsWith(proposal(requestId), "idem-" + requestId);
    service.reconcile(requestId);

    for (String table : List.of("ai_provider_execution", "ai_execution_transition",
        "ai_reconciliation_work")) {
      assertThat(jdbc.queryForObject(
          "SELECT coalesce(string_agg(t::text, ' '), '') FROM core." + table + " t", String.class))
          .as("%s must not carry model output", table)
          .doesNotContain(CANARY);
    }
  }

  // ================================================================================================
  // 7 -- adoption
  // ================================================================================================

  @Test
  @DisplayName("adoption commits the decision and destroys the result in one transaction")
  void adoptionUsesTheV037Path() {
    String requestId = "req-adopt-0000001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    port.succeedsWith(proposal(requestId), "idem-" + requestId);
    service.reconcile(requestId);
    assertThat(results.exists(requestId)).isTrue();

    var adopted = service.adopt(requestId, () -> jdbc.update("""
        INSERT INTO core.ai_execution_transition (request_id, to_state, actor, reason)
        VALUES (?, 'ADOPTED', 'ADOPTER', 'GATE_COMMITTED')
        """, requestId));

    assertThat(adopted).isPresent().get().asString().contains(CANARY);
    assertThat(results.exists(requestId)).isFalse();
    assertThat(ledgerReasons(requestId)).contains("GATE_COMMITTED", "ADOPTED");
  }

  @Test
  @DisplayName("a failed adoption decision preserves the result, still adoptable")
  void adoptionRollsBackTogether() {
    String requestId = "req-adoptfail-0001";
    var port = new FakeDurableExecutionPort();
    var service = serviceWith(port);
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    service.submit(requestId, command(requestId));
    port.succeedsWith(proposal(requestId), "idem-" + requestId);
    service.reconcile(requestId);

    assertThatThrownBy(() -> service.adopt(requestId, () -> {
      throw new IllegalStateException("the gate decision failed");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(results.exists(requestId)).isTrue();
    assertThat(ledgerReasons(requestId)).doesNotContain("ADOPTED");
  }

  @Test
  @DisplayName("adopting an execution with no stored result does nothing")
  void adoptingNothingIsSafe() {
    String requestId = "req-adoptnone-0001";
    var service = serviceWith(new FakeDurableExecutionPort());
    service.admit(requestId, "idem-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");

    assertThat(service.adopt(requestId, () -> {
      throw new AssertionError("the decision must not run when there is nothing to adopt");
    })).isEmpty();
  }

  // -- helpers ---------------------------------------------------------------------------------

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

  private int reconciliationRows(String requestId) {
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM core.ai_reconciliation_work WHERE request_id = ?", Integer.class,
        requestId);
    return count == null ? 0 : count;
  }

  /** A valid {@code diagnostic-proposal.v1}, carrying the canary in its reason text. */
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
