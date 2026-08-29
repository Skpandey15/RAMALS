package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.execution.contractb.FakeDurableExecutionPort.FakeBatch;
import io.ramals.learningplatform.execution.crypto.FakeResultEncryptionKeyProvider;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCodec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
 * Enumeration request-rate discipline (M2-ADR-020 §3.1, §3.2, §7), against real PostgreSQL.
 *
 * <p>W2 found that this ADR bounded the size of one search and said nothing about repeating it.
 * Because a search held no state, every retry repaid its full cost — one orphan in a forty-five
 * candidate window cost about forty-six provider calls per attempt, retried every thirty seconds,
 * against a limit of fifty a minute. A single lost acknowledgement was enough to breach it.
 *
 * <p>The fix is two things that only work together, and most of this file is about that coupling.
 * The memo lets a bounded search <em>resume</em>; the budget stops one pass spending everything. A
 * budget without a memo does not slow a search down, it stops it ever finishing — so the negative
 * control that removes the memo and watches the search livelock is the most important test here.
 *
 * <p>Everything the original ADR guarantees still has to hold while cost is being cut, so these
 * tests assert the guarantees at least as hard as the savings: coverage before {@code ZERO},
 * {@code MULTIPLE} precedence, an unfinished candidate never reading as absence, and no submission
 * ever.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ContractBEnumerationRequestRateIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String KEY_V1 = "contract-b-key-v1";

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
    jdbc.execute("TRUNCATE core.ai_enumeration_no_match, "
        + "core.ai_provider_execution_observation, core.ai_execution_transition, "
        + "core.ai_execution_result, core.ai_reconciliation_work, core.ai_provider_execution "
        + "CASCADE");
    executions = new ProviderExecutionRepository(jdbc);
  }

  private ContractBProperties properties() {
    return new ContractBProperties();
  }

  private ContractBExecutionService instance(DurableExecutionPort port) {
    return instance(port, properties());
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

  /** Sent, unacknowledged: the exact row a lost acknowledgement leaves. */
  private void orphan(String requestId) {
    instance(new FakeDurableExecutionPort()).admit(requestId, "idem-" + requestId,
        "anthropic", "claude-sonnet-5", "diagnostic");
    executions.claimForSubmission(requestId).orElseThrow();
    executions.enqueueReconciliation(requestId);
  }

  private List<String> memo(String requestId) {
    return jdbc.queryForList("SELECT provider_execution_id FROM core.ai_enumeration_no_match "
        + "WHERE request_id = ? ORDER BY provider_execution_id", String.class, requestId);
  }

  // ================================================================================================
  // The memo: what may be remembered, and what must never be
  // ================================================================================================

  @Test
  @DisplayName("only ended, fully-read, non-matching batches are remembered")
  void onlyProvenNegativesAreMemoised() {
    orphan("req-memo-shape");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().withWindow(
        FakeBatch.ended("msgbatch_nomatch_a"),
        FakeBatch.ended("msgbatch_nomatch_b"),
        FakeBatch.inProgress("msgbatch_still_running"));

    instance(port).reconcile("req-memo-shape");

    // The unfinished batch has no results to read, so nothing was learned about it. Remembering it
    // would hand a later search coverage nobody established, and that search could then report
    // ZERO -- which is terminal -- over the candidate most likely to be the orphan.
    assertThat(memo("req-memo-shape"))
        .containsExactly("msgbatch_nomatch_a", "msgbatch_nomatch_b");
  }

  @Test
  @DisplayName("an in-progress candidate is never memoised, however often it is seen")
  void anUnfinishedCandidateIsNeverMemoised() {
    orphan("req-memo-running");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort()
        .withWindow(FakeBatch.inProgress("msgbatch_still_running"));

    for (int pass = 0; pass < 3; pass++) {
      assertThat(instance(port).reconcile("req-memo-running"))
          .isEqualTo(DurableExecutionState.RECONCILING);
    }

    // Three attempts, still nothing remembered, still not terminal. Persistence must not accumulate
    // into a conclusion.
    assertThat(memo("req-memo-running")).isEmpty();
  }

  @Test
  @DisplayName("a batch carrying the key is evidence, not a negative")
  void aMatchIsNeverMemoisedAsANegative() {
    orphan("req-memo-match");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().withWindow(
        FakeBatch.ended("msgbatch_nomatch"),
        FakeBatch.carrying("msgbatch_theone"));

    instance(port).reconcile("req-memo-match");

    assertThat(memo("req-memo-match")).containsExactly("msgbatch_nomatch");
    assertThat(providerExecutionId("req-memo-match")).isEqualTo("msgbatch_theone");
  }

  @Test
  @DisplayName("the memo never enters the observation table or the cost evidence")
  void theMemoIsNotEvidence() {
    orphan("req-memo-not-evidence");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().withWindow(
        FakeBatch.ended("msgbatch_someone_else_a"),
        FakeBatch.ended("msgbatch_someone_else_b"),
        FakeBatch.carrying("msgbatch_ours"));

    instance(port).reconcile("req-memo-not-evidence");

    // Two batches were opened and found to belong to other requests. They are not this request's
    // executions, and putting them in the table that answers "what did this request cost" would
    // corrupt criterion 8's evidence with other people's tokens.
    assertThat(memo("req-memo-not-evidence")).hasSize(2);
    assertThat(jdbc.queryForList("SELECT provider_execution_id "
        + "FROM core.ai_provider_execution_observation WHERE request_id = ?",
        String.class, "req-memo-not-evidence")).containsExactly("msgbatch_ours");
  }

  @Test
  @DisplayName("the memo survives a completely fresh repository and service")
  void theMemoSurvivesReconstruction() {
    orphan("req-memo-restart");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().withWindow(
        FakeBatch.ended("msgbatch_a"),
        FakeBatch.ended("msgbatch_b"),
        FakeBatch.inProgress("msgbatch_pending"));

    instance(port).reconcile("req-memo-restart");
    assertThat(memo("req-memo-restart")).containsExactly("msgbatch_a", "msgbatch_b");

    // A replacement instance holding nothing from the first: new repository, new ledger, new store,
    // new adoption boundary, new service. Recovery state lives in PostgreSQL or it does not exist,
    // and a cache that died with the process would repay its whole cost after every restart.
    FakeDurableExecutionPort afterRestart = new FakeDurableExecutionPort().withWindow(
        FakeBatch.ended("msgbatch_a"),
        FakeBatch.ended("msgbatch_b"),
        FakeBatch.inProgress("msgbatch_pending"));
    instance(afterRestart).reconcile("req-memo-restart");

    assertThat(afterRestart.lastExcludeIds).containsExactlyInAnyOrder("msgbatch_a", "msgbatch_b");
  }

  // ================================================================================================
  // Cumulative coverage: what the memo is allowed to conclude
  // ================================================================================================

  @Test
  @DisplayName("ZERO requires cumulative coverage, never one pass's worth")
  void zeroRequiresCumulativeCoverage() {
    orphan("req-cumulative");
    ContractBProperties tight = properties();
    tight.getReconciliation().setInspectionBudgetPerPass(2);
    FakeBatch[] window = {
        FakeBatch.ended("msgbatch_c1"), FakeBatch.ended("msgbatch_c2"),
        FakeBatch.ended("msgbatch_c3"), FakeBatch.ended("msgbatch_c4"),
        FakeBatch.ended("msgbatch_c5")};

    // Two inspections a pass over five candidates. The first two passes have not covered the
    // window and must not say they have.
    assertThat(instance(new FakeDurableExecutionPort().withWindow(window), tight)
        .reconcile("req-cumulative")).isEqualTo(DurableExecutionState.RECONCILING);
    assertThat(instance(new FakeDurableExecutionPort().withWindow(window), tight)
        .reconcile("req-cumulative")).isEqualTo(DurableExecutionState.RECONCILING);
    assertThat(memo("req-cumulative")).hasSize(4);

    // The third covers the last candidate, and only now is the absence real.
    assertThat(instance(new FakeDurableExecutionPort().withWindow(window), tight)
        .reconcile("req-cumulative")).isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(reason("req-cumulative")).contains("SEARCH_FOUND_NOTHING");
  }

  @Test
  @DisplayName("negative control: without the memo a bounded search livelocks and never converges")
  void withoutTheMemoTheBudgetLivelocks() {
    orphan("req-livelock");
    ContractBProperties tight = properties();
    tight.getReconciliation().setInspectionBudgetPerPass(2);
    FakeBatch[] window = {
        FakeBatch.ended("msgbatch_l1"), FakeBatch.ended("msgbatch_l2"),
        FakeBatch.ended("msgbatch_l3"), FakeBatch.ended("msgbatch_l4"),
        FakeBatch.carrying("msgbatch_l5")};

    for (int pass = 0; pass < 5; pass++) {
      FakeDurableExecutionPort port = new FakeDurableExecutionPort().withWindow(window);
      assertThat(instance(port, tight).reconcile("req-livelock"))
          .isEqualTo(DurableExecutionState.RECONCILING);
      // Discarding the memo is the whole control: it is exactly what a stateless bounded search
      // does, and it is why the budget and the memo are one mechanism rather than two ideas.
      jdbc.update("DELETE FROM core.ai_enumeration_no_match WHERE request_id = ?", "req-livelock");
      assertThat(port.lastExcludeIds).isEmpty();
    }

    // Five passes, ten inspections, and the batch actually carrying the key was never reached.
    // Left alone this ends as horizon exhaustion twenty-six hours later, on an execution that was
    // recoverable the whole time.
    assertThat(providerExecutionId("req-livelock")).isNull();
    assertThat(executions.find("req-livelock").orElseThrow().state().terminal()).isFalse();
  }

  @Test
  @DisplayName("with the memo the same bounded search converges and recovers the orphan")
  void withTheMemoTheSameSearchConverges() {
    orphan("req-converges");
    ContractBProperties tight = properties();
    tight.getReconciliation().setInspectionBudgetPerPass(2);
    FakeBatch[] window = {
        FakeBatch.ended("msgbatch_v1"), FakeBatch.ended("msgbatch_v2"),
        FakeBatch.ended("msgbatch_v3"), FakeBatch.ended("msgbatch_v4"),
        FakeBatch.carrying("msgbatch_v5")};

    for (int pass = 0; pass < 5 && providerExecutionId("req-converges") == null; pass++) {
      instance(new FakeDurableExecutionPort().withWindow(window), tight).reconcile("req-converges");
    }

    assertThat(providerExecutionId("req-converges")).isEqualTo("msgbatch_v5");
  }

  @Test
  @DisplayName("a cached negative beside an unfinished candidate is still not absence")
  void aCachedNegativeDoesNotManufactureAbsence() {
    orphan("req-cached-plus-running");
    FakeBatch[] window = {
        FakeBatch.ended("msgbatch_known"), FakeBatch.inProgress("msgbatch_running")};

    instance(new FakeDurableExecutionPort().withWindow(window)).reconcile("req-cached-plus-running");
    assertThat(memo("req-cached-plus-running")).containsExactly("msgbatch_known");

    FakeDurableExecutionPort second = new FakeDurableExecutionPort().withWindow(window);
    DurableExecutionState state = instance(second).reconcile("req-cached-plus-running");

    // Everything readable has been covered, and that is still not coverage. Complete knowledge of
    // the batches that finished says nothing about the one that has not.
    assertThat(second.lastExcludeIds).containsExactly("msgbatch_known");
    assertThat(state).isEqualTo(DurableExecutionState.RECONCILING);
  }

  @Test
  @DisplayName("MULTIPLE still wins when the budget runs out mid-search")
  void multipleStillWinsUnderABudget() {
    orphan("req-multiple-budget");
    ContractBProperties tight = properties();
    tight.getReconciliation().setInspectionBudgetPerPass(2);

    DurableExecutionState state = instance(new FakeDurableExecutionPort().withWindow(
        FakeBatch.carrying("msgbatch_dup_a"),
        FakeBatch.carrying("msgbatch_dup_b"),
        FakeBatch.ended("msgbatch_never_reached")), tight).reconcile("req-multiple-budget");

    // Two is already more than one and no further looking can reduce it. Degrading this to
    // "try again later" would let the lifecycle keep hunting for a duplicate it has proven.
    assertThat(state).isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(reason("req-multiple-budget")).contains("DUPLICATE_PROVIDER_EXECUTION");
    assertThat(providerExecutionId("req-multiple-budget")).isNull();
  }

  // ================================================================================================
  // The per-pass budget
  // ================================================================================================

  @Test
  @DisplayName("one pass's budget is shared across every orphan it handles")
  void theBudgetIsSharedAcrossThePass() {
    orphan("req-share-a");
    orphan("req-share-b");
    ContractBProperties tight = properties();
    tight.getReconciliation().setInspectionBudgetPerPass(3);
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().withWindow(
        FakeBatch.ended("msgbatch_s1"), FakeBatch.ended("msgbatch_s2"),
        FakeBatch.ended("msgbatch_s3"), FakeBatch.ended("msgbatch_s4"));

    InspectionBudget budget = InspectionBudget.of(3);
    ContractBExecutionService lifecycle = instance(port, tight);
    lifecycle.reconcile("req-share-a", budget);
    lifecycle.reconcile("req-share-b", budget);

    // The first orphan spent the pass's whole allowance, so the second gets none. Per-search bounds
    // would have given it three more, which is how twenty orphans authorise a thousand calls.
    assertThat(budget.remaining()).isZero();
    assertThat(memo("req-share-a")).hasSize(3);
    assertThat(memo("req-share-b")).isEmpty();
  }

  @Test
  @DisplayName("an exhausted budget defers the search rather than performing it")
  void anExhaustedBudgetPerformsNoSearch() {
    orphan("req-no-budget");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort()
        .withWindow(FakeBatch.ended("msgbatch_untouched"));

    DurableExecutionState state =
        instance(port).reconcile("req-no-budget", InspectionBudget.of(0));

    assertThat(state).isEqualTo(DurableExecutionState.RECONCILING);
    assertThat(port.searchCalls.get()).isZero();
  }

  @Test
  @DisplayName("an exhausted budget is not terminal even past the search horizon")
  void anExhaustedBudgetIsNeverTerminal() {
    orphan("req-no-budget-horizon");
    ContractBProperties expired = properties();
    expired.getRecovery().setSearchHorizonMs(0);

    DurableExecutionState state = instance(new FakeDurableExecutionPort(), expired)
        .reconcile("req-no-budget-horizon", InspectionBudget.of(0));

    // The horizon ends a search that looked and could not see. This one never looked, and
    // terminating on evidence we declined to gather would be the fail-open §2 exists to prevent.
    assertThat(state).isEqualTo(DurableExecutionState.RECONCILING);
    assertThat(executions.find("req-no-budget-horizon").orElseThrow().state().terminal()).isFalse();
  }

  @Test
  @DisplayName("a search is never given more inspections than the ADR's own per-search bound")
  void theAdrBoundStillCapsAGenerousBudget() {
    orphan("req-adr-cap");
    ContractBProperties generous = properties();
    generous.getRecovery().setMaxInspectionsPerSearch(4);
    FakeDurableExecutionPort port = new FakeDurableExecutionPort()
        .withWindow(FakeBatch.ended("msgbatch_cap"));

    instance(port, generous).reconcile("req-adr-cap", InspectionBudget.of(1000));

    assertThat(port.lastMaxInspections).isEqualTo(4);
  }

  // ================================================================================================
  // Rate limiting
  // ================================================================================================

  @Test
  @DisplayName("a rate limit defers the durable next attempt by the provider's Retry-After")
  void retryAfterMovesTheDurableNextAttempt() {
    orphan("req-429-retry-after");
    ContractBProperties settings = properties();
    settings.getReconciliation().setBackoffMs(1_000);
    settings.getReconciliation().setMaxBackoffMs(600_000);
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().searchRateLimited(120_000L);

    Instant before = nextAttemptAt("req-429-retry-after");
    worker(port, settings).poll();
    Instant after = nextAttemptAt("req-429-retry-after");

    // Two minutes, because the provider said two minutes. It knows when it will serve again and
    // RAMALS is guessing; the whole point of honouring the header is not to guess lower.
    assertThat(after).isAfter(before.plusSeconds(100));
    assertThat(providerExecutionId("req-429-retry-after")).isNull();
  }

  @Test
  @DisplayName("a rate limit with no Retry-After still backs off, and never terminates")
  void aRateLimitWithoutRetryAfterStillBacksOff() {
    orphan("req-429-no-header");
    ContractBProperties settings = properties();
    settings.getReconciliation().setBackoffMs(45_000);
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().searchRateLimited(null);

    worker(port, settings).poll();

    assertThat(nextAttemptAt("req-429-no-header")).isAfter(Instant.now().plusSeconds(30));
    // Being told to slow down says nothing about whether an orphan exists.
    assertThat(executions.find("req-429-no-header").orElseThrow().state().terminal()).isFalse();
  }

  @Test
  @DisplayName("Retry-After is clamped, so one header cannot push recovery to its horizon")
  void retryAfterIsClamped() {
    orphan("req-429-clamped");
    ContractBProperties settings = properties();
    settings.getReconciliation().setMaxBackoffMs(60_000);
    FakeDurableExecutionPort port =
        new FakeDurableExecutionPort().searchRateLimited(86_400_000L);

    worker(port, settings).poll();

    // A day's wait would consume most of the twenty-six-hour horizon in one step. Honouring the
    // provider does not extend to letting it end a recovery.
    assertThat(nextAttemptAt("req-429-clamped")).isBefore(Instant.now().plusSeconds(120));
  }

  @Test
  @DisplayName("a rate-limited pass stops rather than asking the same exhausted quota again")
  void aRateLimitedPassStops() {
    orphan("req-429-stop-a");
    orphan("req-429-stop-b");
    orphan("req-429-stop-c");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().searchRateLimited(30_000L);

    worker(port, properties()).poll();

    // One call, not three. The limit is organization-wide, so continuing the pass could only fail
    // and only make recovery slower for every other execution waiting on the same quota.
    assertThat(port.searchCalls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("an ordinary outage does not stop the pass")
  void anOutageDoesNotStopThePass() {
    orphan("req-outage-a");
    orphan("req-outage-b");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().searchUnavailable();

    worker(port, properties()).poll();

    // The contrast that makes the rate-limit behaviour deliberate rather than incidental: an
    // unavailable provider costs nobody else anything, so the other orphans are still tried.
    assertThat(port.searchCalls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("repeated failures back off exponentially rather than at a fixed interval")
  void backoffGrowsWithAttempts() {
    orphan("req-backoff");
    ContractBProperties settings = properties();
    settings.getReconciliation().setBackoffMs(1_000);
    settings.getReconciliation().setMaxBackoffMs(3_600_000);
    settings.getReconciliation().setBackoffJitterMs(0);

    executions.recordReconciliationAttempt("req-backoff", 1_000, 3_600_000, 0);
    Instant first = nextAttemptAt("req-backoff");
    jdbc.update("UPDATE core.ai_reconciliation_work SET attempts = 6 WHERE request_id = ?",
        "req-backoff");
    executions.recordReconciliationAttempt("req-backoff", 1_000, 3_600_000, 0);
    Instant seventh = nextAttemptAt("req-backoff");

    // A fixed interval spends the same provider quota on the hundredth failed attempt as on the
    // first, which is how a rate limit becomes self-sustaining.
    assertThat(seventh).isAfter(first.plusSeconds(30));
  }

  @Test
  @DisplayName("backoff is capped, so retries continue meaningfully inside the 26-hour horizon")
  void backoffIsCapped() {
    orphan("req-backoff-cap");
    jdbc.update("UPDATE core.ai_reconciliation_work SET attempts = 40 WHERE request_id = ?",
        "req-backoff-cap");

    executions.recordReconciliationAttempt("req-backoff-cap", 30_000, 900_000, 0);

    // Uncapped, doubling from thirty seconds passes twenty-six hours in about a dozen attempts --
    // so the search would stop being retried long before the horizon that is meant to end it, and
    // the execution would sit non-terminal and unexamined.
    assertThat(nextAttemptAt("req-backoff-cap")).isBefore(Instant.now().plusSeconds(1_000));
  }

  @Test
  @DisplayName("no rate-limit path ever submits")
  void nothingHereEverSubmits() {
    orphan("req-429-no-submit");
    FakeDurableExecutionPort port = new FakeDurableExecutionPort().searchRateLimited(1_000L);

    worker(port, properties()).poll();
    worker(port, properties()).poll();

    assertThat(port.submissions).isEmpty();
  }

  // ================================================================================================
  // Helpers
  // ================================================================================================

  private ContractBReconciliationWorker worker(
      DurableExecutionPort port, ContractBProperties properties) {
    return new ContractBReconciliationWorker(
        new ProviderExecutionRepository(jdbc), instance(port, properties), properties);
  }

  private Instant nextAttemptAt(String requestId) {
    return jdbc.queryForObject(
        "SELECT next_attempt_at FROM core.ai_reconciliation_work WHERE request_id = ?",
        java.sql.Timestamp.class, requestId).toInstant();
  }

  private String providerExecutionId(String requestId) {
    return jdbc.queryForObject(
        "SELECT provider_execution_id FROM core.ai_provider_execution WHERE request_id = ?",
        String.class, requestId);
  }

  private String reason(String requestId) {
    return String.join(",", jdbc.queryForList(
        "SELECT reason FROM core.ai_execution_transition WHERE request_id = ? ORDER BY id",
        String.class, requestId));
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
      throw new IllegalStateException(name + " must be set for this test");
    }
    return value;
  }

}
