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
 * Lost-acknowledgement recovery by `custom_id` enumeration (M2-ADR-020), against real PostgreSQL.
 *
 * <p>Before this existed, "sent, unacknowledged" was terminal by necessity. These tests are about
 * the four things that can now happen instead, and — more importantly — about the three that must
 * still never happen: a resubmission, a chosen duplicate, and an absence inferred from a search that
 * did not finish.
 *
 * <p>Every test asserts the provider submission count. Enumeration is a recovery path, and the
 * failure it exists to avoid is the one where recovery creates the thing it was looking for.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ContractBLostAcknowledgementRecoveryIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String KEY_V1 = "contract-b-key-v1";
  private static final String CANARY = "CANARY-LEARNER-DIAGNOSIS-DO-NOT-PERSIST";
  private static final String FOUND = "msgbatch_recovered00001";
  private static final String OTHER = "msgbatch_duplicate00002";

  private static String databaseUrl;

  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private ProviderExecutionRepository executions;

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
    jdbc.execute("TRUNCATE core.ai_provider_execution_observation, core.ai_execution_transition, "
        + "core.ai_execution_result, core.ai_reconciliation_work, core.ai_provider_execution "
        + "CASCADE");
    executions = new ProviderExecutionRepository(jdbc);
  }

  private ContractBExecutionService instance(DurableExecutionPort port) {
    return instance(port, new ContractBProperties());
  }

  private ContractBExecutionService instance(
      DurableExecutionPort port, ContractBProperties properties) {
    return new ContractBExecutionService(
        new ProviderExecutionRepository(jdbc),
        new ContractBTransitionLedger(jdbc),
        port,
        new ContractBResultStore(jdbc,
            new ResultEnvelopeCodec(
                new FakeResultEncryptionKeyProvider().with(KEY_V1).active(KEY_V1)),
            new ObjectMapper()),
        new ContractBAdoption(jdbc, new DataSourceTransactionManager(source)),
        properties);
  }

  /**
   * Puts one execution into the exact state a lost acknowledgement leaves: sent, no identity.
   *
   * <p>Produced by the write-ahead claim rather than by hand, so the row under test is the one the
   * production path actually writes.
   */
  private long orphan(String requestId) {
    instance(new FakeDurableExecutionPort()).admit(requestId, "idem-" + requestId,
        "custom-" + requestId, "anthropic", "claude-sonnet-5", "diagnostic");
    long fence = executions.claimForSubmission(requestId).orElseThrow();
    assertThat(providerExecutionId(requestId)).isNull();
    return fence;
  }

  private DiscoveredExecution discovered(String batch, String requestId) {
    return new DiscoveredExecution(batch, "custom-" + requestId, "succeeded", 16, 4, 0,
        null, null, "ended");
  }

  // ================================================================================================
  // Exactly one match
  // ================================================================================================

  @Test
  @DisplayName("one match: the identity is adopted, fenced, and reconciliation resumes")
  void exactlyOneMatchIsAdoptedAndResumes() {
    String requestId = "req-rec-one-000001";
    orphan(requestId);
    var fake = new FakeDurableExecutionPort().providerExecutionId(FOUND);
    fake.searchFinds(DurableExecutionSearch.Outcome.ONE, discovered(FOUND, requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);

    // Recovery restores what the lost acknowledgement would have written, then the ordinary path
    // takes over -- so the execution completes rather than merely stopping being an orphan.
    assertThat(instance(fake).reconcile(requestId)).isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(providerExecutionId(requestId)).isEqualTo(FOUND);
    assertThat(ledgerReasons(requestId)).contains("IDENTITY_RECOVERED");
    assertThat(fake.submissions)
        .as("recovery must never create the execution it went looking for")
        .isEmpty();
  }

  @Test
  @DisplayName("one match: the discovered execution is recorded with its usage")
  void theAdoptedExecutionIsAccountedFor() {
    String requestId = "req-rec-cost-00001";
    orphan(requestId);
    var fake = new FakeDurableExecutionPort().providerExecutionId(FOUND);
    fake.searchFinds(DurableExecutionSearch.Outcome.ONE, discovered(FOUND, requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    instance(fake).reconcile(requestId);

    List<DiscoveredExecution> observed = executions.observations(requestId);
    assertThat(observed).hasSize(1);
    assertThat(observed.get(0).providerExecutionId()).isEqualTo(FOUND);
    // Tokens travel with the discovery: an execution known to exist but whose usage is unrecorded
    // is invisible in the bill however visible it is in a log.
    assertThat(observed.get(0).inputTokens()).isEqualTo(16);
    assertThat(observed.get(0).outputTokens()).isEqualTo(4);
  }

  // ================================================================================================
  // Zero, and the difference between zero and unfinished
  // ================================================================================================

  @Test
  @DisplayName("zero matches: conclusive, so the execution becomes INDETERMINATE")
  void zeroMatchesIsTerminal() {
    String requestId = "req-rec-zero-00001";
    orphan(requestId);
    var fake = new FakeDurableExecutionPort().searchFinds(DurableExecutionSearch.Outcome.ZERO);

    assertThat(instance(fake).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(ledgerReasons(requestId)).contains("SEARCH_FOUND_NOTHING");
    assertThat(fake.submissions).isEmpty();
  }

  @Test
  @DisplayName("inconclusive: NOT treated as absence, and the execution stays recoverable")
  void inconclusiveIsNotAbsence() {
    String requestId = "req-rec-incon-0001";
    orphan(requestId);
    // A candidate that has not ended has no results to read. Calling that "no orphan" would report
    // absence at the exact moment an orphan is most likely to be running.
    var fake = new FakeDurableExecutionPort()
        .searchFinds(DurableExecutionSearch.Outcome.INCONCLUSIVE);

    assertThat(instance(fake).reconcile(requestId)).isEqualTo(DurableExecutionState.RECONCILING);
    assertThat(DurableExecutionState.of(state(requestId)).terminal()).isFalse();
    assertThat(ledgerReasons(requestId)).doesNotContain("SEARCH_FOUND_NOTHING");
  }

  @Test
  @DisplayName("inconclusive past the horizon: terminal, and recorded as exhausted not as zero")
  void inconclusivePastTheHorizonIsExhausted() {
    String requestId = "req-rec-horizon-01";
    orphan(requestId);
    var properties = new ContractBProperties();
    properties.getRecovery().setSearchHorizonMs(0);
    var fake = new FakeDurableExecutionPort()
        .searchFinds(DurableExecutionSearch.Outcome.INCONCLUSIVE);

    assertThat(instance(fake, properties).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    // The two describe different worlds -- one where we looked and there was nothing, one where we
    // looked and could not see -- and the ledger keeps them apart.
    assertThat(ledgerReasons(requestId))
        .contains("SEARCH_HORIZON_EXHAUSTED")
        .doesNotContain("SEARCH_FOUND_NOTHING");
  }

  // ================================================================================================
  // Multiple matches
  // ================================================================================================

  @Test
  @DisplayName("multiple matches: every one recorded, none adopted, operator required")
  void multipleMatchesAreRecordedAndNoneIsChosen() {
    String requestId = "req-rec-dupe-00001";
    orphan(requestId);
    var fake = new FakeDurableExecutionPort().searchFinds(
        DurableExecutionSearch.Outcome.MULTIPLE,
        discovered(FOUND, requestId), discovered(OTHER, requestId));

    assertThat(instance(fake).reconcile(requestId))
        .isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);

    // Adopting the first would attribute a diagnosis to an arbitrary execution; adopting the newest
    // would silently prefer a duplicate over the original. Neither is chosen.
    assertThat(providerExecutionId(requestId)).isNull();
    assertThat(executions.observations(requestId))
        .extracting(DiscoveredExecution::providerExecutionId)
        .containsExactlyInAnyOrder(FOUND, OTHER);
    assertThat(ledgerReasons(requestId)).contains("DUPLICATE_PROVIDER_EXECUTION");
    assertThat(fake.submissions).isEmpty();
  }

  @Test
  @DisplayName("multiple matches: a repeated search records no second copy of the same evidence")
  void duplicateEvidenceIsNotMultipliedByRepeatedSearches() {
    String requestId = "req-rec-dupe2-0001";
    orphan(requestId);
    var fake = new FakeDurableExecutionPort().searchFinds(
        DurableExecutionSearch.Outcome.MULTIPLE,
        discovered(FOUND, requestId), discovered(OTHER, requestId));
    instance(fake).reconcile(requestId);

    // Terminal, so a second reconcile does not even search -- but the evidence must be stable even
    // if it did. A count of observations must mean "how many executions exist".
    instance(fake).reconcile(requestId);
    assertThat(executions.observations(requestId)).hasSize(2);
  }

  // ================================================================================================
  // Failure, restart and fencing
  // ================================================================================================

  @Test
  @DisplayName("provider unavailable during enumeration: not terminal, tried again later")
  void anEnumerationOutageIsNotAnOutcome() {
    String requestId = "req-rec-outage-001";
    orphan(requestId);
    var offline = new FakeDurableExecutionPort().searchUnavailable();

    // Being unable to look says nothing about whether an orphan exists.
    assertThat(instance(offline).reconcile(requestId))
        .isEqualTo(DurableExecutionState.RECONCILING);
    assertThat(DurableExecutionState.of(state(requestId)).terminal()).isFalse();

    var fake = new FakeDurableExecutionPort().providerExecutionId(FOUND);
    fake.searchFinds(DurableExecutionSearch.Outcome.ONE, discovered(FOUND, requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    assertThat(instance(fake).reconcile(requestId)).isEqualTo(DurableExecutionState.SUCCEEDED);
  }

  @Test
  @DisplayName("restart during orphan reconciliation: the replacement searches again and recovers")
  void aRestartDuringRecoveryFindsItAgain() {
    String requestId = "req-rec-restart-01";
    orphan(requestId);
    var fake = new FakeDurableExecutionPort().providerExecutionId(FOUND);
    fake.searchFinds(DurableExecutionSearch.Outcome.ONE, discovered(FOUND, requestId));
    var crashing = new CrashingDurableExecutionPort(fake)
        .dieAt(CrashingDurableExecutionPort.When.AFTER_SEARCH);

    // The orphan was found and the process died before the identity could be written down.
    assertThatThrownBy(() -> instance(crashing).reconcile(requestId))
        .isInstanceOf(SimulatedProcessDeath.class);
    assertThat(providerExecutionId(requestId))
        .as("nothing was adopted, so the replacement must not assume it was")
        .isNull();

    // A fresh instance repeats the search and reaches the same conclusion. Enumeration is a read,
    // so repeating it is free of consequence.
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    assertThat(instance(crashing.survive()).reconcile(requestId))
        .isEqualTo(DurableExecutionState.SUCCEEDED);
    assertThat(providerExecutionId(requestId)).isEqualTo(FOUND);
    assertThat(fake.submissions).isEmpty();
  }

  @Test
  @DisplayName("fence lost while adopting: the recovered identity is not written over another's")
  void aLostFenceCannotOverwriteAnAdoptedIdentity() {
    String requestId = "req-rec-fence-0001";
    long staleFence = orphan(requestId);

    // Another worker adopts first, under a fence this caller does not hold.
    assertThat(executions.adoptRecoveredIdentity(requestId, staleFence, FOUND)).isTrue();

    // The slow worker's own search finished afterwards, naming a different execution. Its write
    // must not land: the gap it was going to fill is filled, and by somebody with a live claim.
    assertThat(executions.adoptRecoveredIdentity(requestId, staleFence, OTHER)).isFalse();
    assertThat(executions.adoptRecoveredIdentity(requestId, staleFence + 5, OTHER)).isFalse();
    assertThat(providerExecutionId(requestId)).isEqualTo(FOUND);
  }

  @Test
  @DisplayName("fence lost mid-recovery: the lifecycle defers rather than overwriting")
  void aLifecycleThatLosesTheFenceDefers() {
    String requestId = "req-rec-fence2-001";
    long fence = orphan(requestId);
    // A concurrent worker wins the race while this one was enumerating.
    executions.adoptRecoveredIdentity(requestId, fence, FOUND);

    var fake = new FakeDurableExecutionPort().providerExecutionId(OTHER);
    fake.searchFinds(DurableExecutionSearch.Outcome.ONE, discovered(OTHER, requestId));
    fake.state("RUNNING", "in_progress");
    instance(fake).reconcile(requestId);

    assertThat(providerExecutionId(requestId))
        .as("the losing worker must not replace an identity somebody else adopted")
        .isEqualTo(FOUND);
    assertThat(fake.submissions).isEmpty();
  }

  @Test
  @DisplayName("no recovery path ever submits")
  void noRecoveryPathSubmits() {
    for (DurableExecutionSearch.Outcome outcome : DurableExecutionSearch.Outcome.values()) {
      String requestId = "req-rec-nosub-" + outcome.ordinal() + "001";
      orphan(requestId);
      var fake = new FakeDurableExecutionPort().providerExecutionId(FOUND);
      if (outcome == DurableExecutionSearch.Outcome.MULTIPLE) {
        fake.searchFinds(outcome, discovered(FOUND, requestId), discovered(OTHER, requestId));
      } else if (outcome == DurableExecutionSearch.Outcome.ONE) {
        fake.searchFinds(outcome, discovered(FOUND, requestId));
        fake.succeedsWith(proposal(requestId), "custom-" + requestId);
      } else {
        fake.searchFinds(outcome);
      }

      instance(fake).reconcile(requestId);
      assertThat(fake.submissions)
          .as("%s must not submit", outcome)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("observations carry no model output")
  void observationsCarryNoPayload() {
    String requestId = "req-rec-nopay-0001";
    orphan(requestId);
    var fake = new FakeDurableExecutionPort().providerExecutionId(FOUND);
    fake.searchFinds(DurableExecutionSearch.Outcome.ONE, discovered(FOUND, requestId));
    fake.succeedsWith(proposal(requestId), "custom-" + requestId);
    instance(fake).reconcile(requestId);

    assertThat(jdbc.queryForObject(
        "SELECT coalesce(string_agg(t::text, ' '), '') "
            + "FROM core.ai_provider_execution_observation t", String.class))
        .doesNotContain(CANARY);
  }

  @Test
  @DisplayName("an observation cannot be rewritten or erased")
  void observationsAreAppendOnly() {
    String requestId = "req-rec-append-001";
    orphan(requestId);
    executions.recordObservation(requestId, discovered(FOUND, requestId), "ENUMERATION");

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE core.ai_provider_execution_observation SET outcome = 'errored' WHERE request_id = ?",
        requestId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update(
        "DELETE FROM core.ai_provider_execution_observation WHERE request_id = ?", requestId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  // -- helpers ---------------------------------------------------------------------------------

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
