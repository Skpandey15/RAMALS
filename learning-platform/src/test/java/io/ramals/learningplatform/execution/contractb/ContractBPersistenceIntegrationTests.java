package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.execution.crypto.FakeResultEncryptionKeyProvider;
import io.ramals.learningplatform.execution.crypto.ResultEncryptionKeyUnavailableException;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCodec;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCorruptException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * `V037`'s definition of done: M2-ADR-018 criteria 6, 7 and 8, against real PostgreSQL.
 *
 * <p>Reclassified from preconditions by M2-ADR-019 §6 — they describe `V037`'s own content, so no
 * ordering could make them true beforehand. They are proven here instead, and `V037` is not complete
 * until they hold.
 *
 * <p><b>6 — access control.</b> The access matrix of M2-ADR-018 §3, asserted from the catalogue
 * rather than only by probing. A probe can be denied for the wrong reason: a `DELETE` whose `WHERE`
 * clause needs `SELECT` fails on the `SELECT`, which would let a stray `DELETE` grant pass
 * unnoticed. That exact defect was found in the M2-ADR-019 executable proof, and the catalogue
 * assertion is what caught it.
 *
 * <p><b>7 — transactional adoption.</b> Commit deletes the result; rollback preserves it.
 *
 * <p><b>8 — purge.</b> Terminal-state protection, the ceiling, idempotency, the floor, and the
 * evidence that must survive.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ContractBPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final String KEY_V1 = "contract-b-key-v1";

  /** Stands in for restricted model output. Distinctive so a partial leak still matches. */
  private static final String CANARY = "CANARY-LEARNER-DIAGNOSIS-DO-NOT-PERSIST";

  private static final List<String> CONTRACT_B_TABLES = List.of(
      "ai_provider_execution", "ai_execution_result",
      "ai_execution_transition", "ai_reconciliation_work");

  private static String databaseUrl;

  private JdbcTemplate migration;
  private JdbcTemplate runtime;
  private ContractBResultStore store;
  private ContractBAdoption adoption;
  private ContractBResultPurge purge;

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
            ELSE
              ALTER ROLE ramals_core_runtime WITH LOGIN PASSWORD 'm0-t05-runtime-test';
            END IF;
            -- The AI plane role, so its absence of grants is asserted rather than assumed. V037
            -- skips its revokes when the role is missing, which would make this test vacuous.
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
              CREATE ROLE ramals_ai_runtime LOGIN PASSWORD 'm0-t05-ai-test';
            END IF;
            -- Stands in for the reporting/analytics/evaluation principals of M2-ADR-018 §3. They
            -- hold no grant, and a role that does not exist cannot demonstrate that.
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_test_analytics') THEN
              CREATE ROLE ramals_test_analytics LOGIN PASSWORD 'm0-t05-analytics-test';
            END IF;
          END
          $$;
          """);
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + admin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + MIGRATION_USER
          + ", " + RUNTIME_USER + ", ramals_ai_runtime, ramals_test_analytics");
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
    migration = jdbc(MIGRATION_USER, MIGRATION_PASSWORD);
    // One DataSource instance shared by the template and the transaction manager. Spring binds a
    // transaction's connection to the thread keyed by DataSource, so two instances would leave the
    // adoption delete and the decision write on separate connections -- and the rollback test would
    // pass for the wrong reason.
    DriverManagerDataSource runtimeSource =
        new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
    runtime = new JdbcTemplate(runtimeSource);

    // Contract B tables only. Contract A's rows are not touched by anything in this class.
    migration.execute("TRUNCATE core.ai_execution_transition, core.ai_execution_result, "
        + "core.ai_reconciliation_work, core.ai_provider_execution CASCADE");

    var keys = new FakeResultEncryptionKeyProvider().with(KEY_V1).active(KEY_V1);
    ResultEnvelopeCodec codec = new ResultEnvelopeCodec(keys);
    store = new ContractBResultStore(runtime, codec, new ObjectMapper());
    adoption = new ContractBAdoption(runtime, new DataSourceTransactionManager(runtimeSource));
    purge = new ContractBResultPurge(runtime);
  }

  // ================================================================================================
  // Criterion 6 -- the access matrix of M2-ADR-018 §3
  // ================================================================================================

  @Test
  @DisplayName("6a: only migration and runtime hold any privilege on the result table")
  void noOtherRoleHoldsAnyGrantOnTheResultTable() {
    // The load-bearing assertion, and stated as an invariant rather than as a list of revokes:
    // reporting, analytics and evaluation roles do not exist in this schema yet, so a REVOKE naming
    // one would fail today and a probe against one would be vacuous. Asserting that *no other role*
    // holds anything survives such a role being added later, which a revoke written now would not.
    assertThat(granteesOn("ai_execution_result"))
        .as("M2-ADR-018 §3 grants the result table to ramals_core_runtime and nobody else")
        .containsExactlyInAnyOrder(MIGRATION_USER, RUNTIME_USER);
  }

  @Test
  @DisplayName("6b: the runtime holds SELECT, INSERT and DELETE on the result table -- never UPDATE")
  void runtimeHoldsExactlyTheMatrixPrivileges() {
    // UPDATE arrives by default. V002 sets ALTER DEFAULT PRIVILEGES granting all four privileges on
    // every future core table to the runtime role, so this passes only because V037 revokes it
    // explicitly. Remove that revoke and this test fails -- which is the point of asserting the
    // exact set rather than merely the absence of UPDATE.
    assertThat(privilegesOn("ai_execution_result", RUNTIME_USER))
        .containsExactlyInAnyOrder("SELECT", "INSERT", "DELETE");
  }

  @Test
  @DisplayName("6c: an UPDATE on a stored result is refused")
  void aStoredResultCannotBeRewritten() {
    seedExecution("req-update-0001", "SUCCEEDED", 0);
    store.store("req-update-0001", "msgbatch_update01", proposal("req-update-0001"));

    // Two independent controls, and they fail differently: a dropped trigger leaves the missing
    // grant, a widened grant leaves the trigger. Privilege denial is checked first because it
    // fires before any trigger would.
    assertThatThrownBy(() -> runtime.update(
        "UPDATE core.ai_execution_result SET encryption_key_id = 'x' WHERE request_id = ?",
        "req-update-0001"))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(() -> migration.update(
        "UPDATE core.ai_execution_result SET encryption_key_id = 'x' WHERE request_id = ?",
        "req-update-0001"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
  }

  @Test
  @DisplayName("6d: the AI plane holds no privilege on any Contract B table")
  void theAiPlaneHoldsNothing() {
    for (String table : CONTRACT_B_TABLES) {
      assertThat(privilegesOn(table, "ramals_ai_runtime"))
          .as("%s must be unreachable from the AI plane (M2-ADR-017 §1)", table)
          .isEmpty();
    }
    assertThat(functionExecutors())
        .as("the AI plane must not be able to invoke either purge mechanism")
        .doesNotContain("ramals_ai_runtime");
  }

  @Test
  @DisplayName("6e: an analytics-shaped role can reach neither the result nor the purge")
  void anAnalyticsRoleIsRefused() {
    seedExecution("req-analytics-01", "SUCCEEDED", 0);
    store.store("req-analytics-01", "msgbatch_analytic", proposal("req-analytics-01"));
    JdbcTemplate analytics = jdbc("ramals_test_analytics", "m0-t05-analytics-test");

    assertThat(privilegesOn("ai_execution_result", "ramals_test_analytics")).isEmpty();
    assertThatThrownBy(() ->
        analytics.queryForObject("SELECT count(*) FROM core.ai_execution_result", Integer.class))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> analytics.queryForObject(
        "SELECT core.purge_expired_ai_execution_results(30)", Integer.class))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("6f: the transition ledger is append-only by grant and by trigger")
  void theLedgerCannotBeRewrittenOrErased() {
    assertThat(privilegesOn("ai_execution_transition", RUNTIME_USER))
        .containsExactlyInAnyOrder("SELECT", "INSERT");

    seedExecution("req-ledger-0001", "SUCCEEDED", 0);
    assertThatThrownBy(() -> migration.update(
        "DELETE FROM core.ai_execution_transition WHERE request_id = ?", "req-ledger-0001"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("6g: neither purge mechanism is executable by PUBLIC")
  void purgeIsNotPublic() {
    assertThat(functionExecutors())
        .as("PUBLIC EXECUTE would make the sweep reachable by any role that can connect")
        .doesNotContain("PUBLIC")
        .contains(RUNTIME_USER);
  }

  // ================================================================================================
  // Criterion 7 -- transactional adoption
  // ================================================================================================

  @Test
  @DisplayName("7a: committing the adoption decision deletes the result in the same transaction")
  void commitDeletesTheResult() {
    seedExecution("req-adopt-0001", "SUCCEEDED", 0);
    store.store("req-adopt-0001", "msgbatch_adopt0001", proposal("req-adopt-0001"));
    assertThat(store.exists("req-adopt-0001")).isTrue();

    var adopted = adoption.adopt("req-adopt-0001", () -> {
      // Stands in for the deterministic gate decision: an ordinary write in the same transaction.
      runtime.update("""
          INSERT INTO core.ai_execution_transition (request_id, to_state, actor, reason)
          VALUES (?, 'ADOPTED', 'ADOPTER', 'GATE_COMMITTED')
          """, "req-adopt-0001");
      return "committed";
    });

    assertThat(adopted.decision()).isEqualTo("committed");
    assertThat(adopted.resultsRemoved()).isEqualTo(1);
    assertThat(store.exists("req-adopt-0001")).isFalse();
    assertThat(ledgerStates("req-adopt-0001")).contains("ADOPTED", "PURGED_ON_ADOPTION");
  }

  @Test
  @DisplayName("7b: a failed adoption rolls back and the result survives, still adoptable")
  void rollbackPreservesTheResult() {
    seedExecution("req-rollback-001", "SUCCEEDED", 0);
    store.store("req-rollback-001", "msgbatch_rollback1", proposal("req-rollback-001"));

    assertThatThrownBy(() -> adoption.adopt("req-rollback-001", () -> {
      runtime.update("""
          INSERT INTO core.ai_execution_transition (request_id, to_state, actor, reason)
          VALUES (?, 'ADOPTED', 'ADOPTER', 'GATE_COMMITTED')
          """, "req-rollback-001");
      throw new IllegalStateException("the gate decision failed");
    })).isInstanceOf(IllegalStateException.class);

    // The result is the artifact that makes the execution recoverable. Losing it on a failed
    // adoption would convert a recoverable execution into an unexplained one.
    assertThat(store.exists("req-rollback-001")).isTrue();
    assertThat(store.read("req-rollback-001")).map(json -> json.contains(CANARY)).contains(true);
    // The decision write rolled back with it: no half-adopted state, and no purge claim.
    assertThat(ledgerStates("req-rollback-001"))
        .doesNotContain("ADOPTED")
        .doesNotContain("PURGED_ON_ADOPTION");
  }

  @Test
  @DisplayName("7c: adopting twice is a no-op that writes no second purge claim")
  void adoptingTwiceIsIdempotent() {
    seedExecution("req-adopt-twice1", "SUCCEEDED", 0);
    store.store("req-adopt-twice1", "msgbatch_twice0001", proposal("req-adopt-twice1"));

    assertThat(adoption.adopt("req-adopt-twice1", () -> 1).resultsRemoved()).isEqualTo(1);
    assertThat(adoption.adopt("req-adopt-twice1", () -> 2).resultsRemoved()).isZero();
    assertThat(purgeLedgerCount("req-adopt-twice1", "PURGED_ON_ADOPTION")).isEqualTo(1);
  }

  // ================================================================================================
  // Criterion 8 -- purge
  // ================================================================================================

  @Test
  @DisplayName("8a: a live execution's result is never swept, however old")
  void aLiveExecutionIsNeverPurged() {
    // The check most likely to be skipped and the most expensive to get wrong: it is the difference
    // between a retention control and a data-loss bug (M2-ADR-019 §7, proof 7). This row is 45 days
    // old and would be eligible on age alone.
    seedExecution("req-live-000001", "RUNNING", 45);
    seedAgedResult("req-live-000001", 45);
    seedExecution("req-reconciling01", "RECONCILING", 45);
    seedAgedResult("req-reconciling01", 45);

    assertThat(purge.sweep(30)).isZero();
    assertThat(store.exists("req-live-000001")).isTrue();
    assertThat(store.exists("req-reconciling01")).isTrue();
  }

  @Test
  @DisplayName("8b: the sweep removes terminal results past the window and keeps those inside it")
  void theSweepRespectsTheWindowAndTerminalState() {
    seedExecution("req-expired-0001", "SUCCEEDED", 45);
    seedAgedResult("req-expired-0001", 45);
    seedExecution("req-fresh-000001", "SUCCEEDED", 2);
    seedAgedResult("req-fresh-000001", 2);
    seedExecution("req-live-000002", "RUNNING", 45);
    seedAgedResult("req-live-000002", 45);

    assertThat(purge.sweep(30)).isEqualTo(1);
    assertThat(store.exists("req-expired-0001")).isFalse();
    assertThat(store.exists("req-fresh-000001")).isTrue();
    assertThat(store.exists("req-live-000002")).isTrue();
    assertThat(ledgerStates("req-expired-0001")).contains("PURGED_ON_CEILING");
  }

  @Test
  @DisplayName("8c: a repeated sweep removes nothing and writes no second ledger entry")
  void theSweepIsIdempotent() {
    seedExecution("req-expired-0002", "SUCCEEDED", 45);
    seedAgedResult("req-expired-0002", 45);

    assertThat(purge.sweep(30)).isEqualTo(1);
    assertThat(purge.sweep(30)).isZero();
    assertThat(purge.sweep()).isZero();
    assertThat(purgeLedgerCount("req-expired-0002", "PURGED_ON_CEILING")).isEqualTo(1);
  }

  @Test
  @DisplayName("8d: the sweep refuses a window below the floor or above the ceiling")
  void theSweepRefusesAnUnboundedWindow() {
    seedExecution("req-floor-000001", "SUCCEEDED", 1);
    seedAgedResult("req-floor-000001", 1);

    // Zero would delete everything including a result stored a second ago, which is never what an
    // operator means and is not recoverable.
    assertThatThrownBy(() -> purge.sweep(0))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("at least 1");
    // Above the ceiling would keep RESTRICTED content past the retention this classification was
    // approved under -- and past the 29 days the provider itself keeps a batch result.
    assertThatThrownBy(() -> purge.sweep(31))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ceiling");

    assertThat(store.exists("req-floor-000001")).isTrue();
  }

  @Test
  @DisplayName("8d2: a sweep that cannot run alerts rather than failing quietly")
  void aFailedSweepAlerts() {
    // §10's last row: results outliving the ceiling is a governance failure, not a backlog. The
    // failure is reported and rethrown -- swallowing it would turn a retention breach into silence.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    root.addAppender(captured);
    try {
      assertThatThrownBy(() -> purge.sweep(0)).isInstanceOf(DataAccessException.class);
      assertThat(captured.list).anyMatch(event ->
          event.getLevel() == ch.qos.logback.classic.Level.ERROR
              && event.getFormattedMessage().contains("governance failure"));
    } finally {
      root.detachAppender(captured);
    }
  }

  @Test
  @DisplayName("8e: everything M2-ADR-019 §1 retains survives the purge")
  void auditEvidenceSurvivesThePurge() {
    seedExecution("req-evidence-001", "SUCCEEDED", 45);
    seedAgedResult("req-evidence-001", 45);
    assertThat(purge.sweep(30)).isEqualTo(1);

    Map<String, Object> kept = runtime.queryForMap("""
        SELECT request_id, provider_execution_id, custom_id, model, state,
               input_tokens, output_tokens, estimated_cost_usd, admitted_at, terminal_at
          FROM core.ai_provider_execution WHERE request_id = ?
        """, "req-evidence-001");

    // An auditor can prove the execution occurred, when, against which provider execution, at what
    // cost, and that its result was purged -- and cannot recover what the model said.
    assertThat(kept.get("provider_execution_id")).asString().startsWith("msgbatch_");
    assertThat(kept.get("custom_id")).asString().isNotEmpty();
    assertThat(kept.get("input_tokens")).isEqualTo(16);
    assertThat(kept.get("estimated_cost_usd")).isNotNull();
    assertThat(kept.get("terminal_at")).isNotNull();
    assertThat(ledgerStates("req-evidence-001")).contains("RESULT_STORED", "PURGED_ON_CEILING");
  }

  @Test
  @DisplayName("8f: no surviving surface can reconstruct a purged result")
  void nothingSurvivingCarriesThePayload() {
    seedExecution("req-canary-00001", "SUCCEEDED", 45);
    seedAgedResult("req-canary-00001", 45);
    assertThat(purge.sweep(30)).isEqualTo(1);

    for (String table : List.of("ai_provider_execution", "ai_execution_transition",
        "ai_reconciliation_work")) {
      String dump = runtime.queryForObject(
          "SELECT coalesce(string_agg(t::text, ' '), '') FROM core." + table + " t", String.class);
      assertThat(dump).as("%s must not carry result content", table).doesNotContain(CANARY);
    }
    assertThat(runtime.queryForObject(
        "SELECT count(*) FROM core.ai_execution_result", Integer.class)).isZero();
  }

  // ================================================================================================
  // Schema fail-closed, and the read-path refusals of §10 against the real table
  // ================================================================================================

  @Test
  @DisplayName("plaintext cannot be committed to the ciphertext column")
  void plaintextIsRejectedByTheSchema() {
    seedExecution("req-plaintext-01", "SUCCEEDED", 0);
    // The specific accident this design most fears: the normalized proposal written straight into
    // the column the envelope belongs in. A JSON document starts with '{' and fails the envelope
    // shape check, so it cannot be committed rather than merely being unlikely to be.
    assertThatThrownBy(() -> migration.update("""
        INSERT INTO core.ai_execution_result
          (request_id, provider_execution_id, normalized_result, encryption_key_id,
           result_digest, result_schema, stored_at, purge_after)
        VALUES (?, 'msgbatch_plaintext', convert_to(?, 'UTF8'), 'contract-b-key-v1',
                repeat('a', 64), 'diagnostic-proposal.v1',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')
        """, "req-plaintext-01", proposal("req-plaintext-01")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_ai_execution_result_envelope");
  }

  @Test
  @DisplayName("a row cannot be written past the 30-day ceiling")
  void theCeilingIsStructural() {
    seedExecution("req-ceiling-0001", "SUCCEEDED", 0);
    assertThatThrownBy(() -> insertSealed("req-ceiling-0001", 0, 31))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_ai_execution_result_ceiling");
  }

  @Test
  @DisplayName("an unknown result schema is refused")
  void anUnnamedSchemaIsRefused() {
    seedExecution("req-schema-00001", "SUCCEEDED", 0);
    assertThatThrownBy(() -> migration.update("""
        INSERT INTO core.ai_execution_result
          (request_id, provider_execution_id, normalized_result, encryption_key_id,
           result_digest, result_schema, stored_at, purge_after)
        VALUES (?, 'msgbatch_schema0001', ?, 'contract-b-key-v1', repeat('a', 64),
                'raw-provider-response', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')
        """, "req-schema-00001", sealedEnvelope("req-schema-00001")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_ai_execution_result_schema");
  }

  @Test
  @DisplayName("a result round-trips, and an absent one is absent rather than an error")
  void theStoreRoundTrips() {
    seedExecution("req-roundtrip-01", "SUCCEEDED", 0);
    var stored = store.store("req-roundtrip-01", "msgbatch_roundtrip", proposal("req-roundtrip-01"));

    assertThat(stored.keyId()).isEqualTo(KEY_V1);
    assertThat(store.read("req-roundtrip-01")).isPresent().get().asString().contains(CANARY);
    assertThat(store.read("req-never-stored1")).isEmpty();
  }

  @Test
  @DisplayName("a result moved to another request's row fails to authenticate")
  void aMovedCiphertextIsRefusedAgainstTheRealTable() {
    seedExecution("req-source-000001", "SUCCEEDED", 0);
    seedExecution("req-target-000001", "SUCCEEDED", 0);
    store.store("req-source-000001", "msgbatch_source001", proposal("req-source-000001"));

    // Exactly the attack the AAD binding exists to stop, performed against the real column: the
    // ciphertext is copied verbatim onto a different learner's row.
    migration.update("""
        INSERT INTO core.ai_execution_result
          (request_id, provider_execution_id, normalized_result, encryption_key_id,
           result_digest, result_schema, stored_at, purge_after)
        SELECT ?, provider_execution_id, normalized_result, encryption_key_id,
               result_digest, result_schema, CURRENT_TIMESTAMP,
               CURRENT_TIMESTAMP + INTERVAL '1 day'
          FROM core.ai_execution_result WHERE request_id = ?
        """, "req-target-000001", "req-source-000001");

    assertThatThrownBy(() -> store.read("req-target-000001"))
        .isInstanceOf(ResultEnvelopeCorruptException.class);
    // And the row is not deleted: it is evidence (M2-ADR-018 §10).
    assertThat(store.exists("req-target-000001")).isTrue();
  }

  @Test
  @DisplayName("a result whose key is gone is unavailable, never absent")
  void aMissingKeyIsNotAbsence() {
    seedExecution("req-nokey-000001", "SUCCEEDED", 0);
    store.store("req-nokey-000001", "msgbatch_nokey0001", proposal("req-nokey-000001"));

    // Reporting this as absent would look like a clean re-runnable request and could resubmit to
    // the provider -- the failure M2-ADR-018 §10 singles out.
    var withoutKey = new ContractBResultStore(runtime,
        new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider().with("contract-b-key-v9")),
        new ObjectMapper());
    assertThatThrownBy(() -> withoutKey.read("req-nokey-000001"))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class);
    assertThat(store.exists("req-nokey-000001")).isTrue();
  }

  @Test
  @DisplayName("a payload outside the committed schema is refused before anything is written")
  void anInvalidPayloadIsRefusedBeforeEncryption() {
    seedExecution("req-invalid-0001", "SUCCEEDED", 0);

    // A raw provider body carrying a reasoning block. It never reaches a cipher and never reaches
    // the column, and the refusal is a reason code rather than a stack trace over the payload.
    String withReasoning = """
        {"contractVersion":"1.0","thinking":"step one, the learner ...","diagnoses":[]}
        """;
    assertThatThrownBy(() -> store.store("req-invalid-0001", "msgbatch_invalid01", withReasoning))
        .isInstanceOf(ContractBResultRejectedException.class);
    assertThat(store.exists("req-invalid-0001")).isFalse();
  }

  @Test
  @DisplayName("a field outside the contract cannot survive into the ciphertext")
  void extraFieldsHaveNowhereToGo() {
    seedExecution("req-extra-000001", "SUCCEEDED", 0);
    // Valid against the contract, plus a reasoning trace. The proposal is accepted; the trace is
    // not stripped so much as given nowhere to go, because what gets sealed is rebuilt from the
    // parsed record.
    String withExtras = proposal("req-extra-000001")
        .replaceFirst("^\\{", "{\"thinking\":\"CHAIN-OF-THOUGHT-MUST-NOT-PERSIST\",");
    store.store("req-extra-000001", "msgbatch_extra0001", withExtras);

    assertThat(store.read("req-extra-000001")).isPresent().get().asString()
        .contains(CANARY)
        .doesNotContain("CHAIN-OF-THOUGHT-MUST-NOT-PERSIST")
        .doesNotContain("thinking");
  }

  @Test
  @DisplayName("Contract A's tables are untouched by this migration")
  void contractAIsUnchanged() {
    // M2-ADR-017 §3/§5: the S1-S4 qualification stays valid because V037 alters no table it
    // exercised. Asserted by column list, which is the same way V023's structural-redaction
    // guarantee is verified.
    for (String table : List.of("ai_execution", "ai_execution_event", "ai_execution_dispatch")) {
      List<String> columns = runtime.queryForList("""
          SELECT column_name FROM information_schema.columns
           WHERE table_schema = 'core' AND table_name = ?
          """, String.class, table);
      assertThat(columns)
          .as("%s must gain no Contract B column", table)
          .doesNotContain("normalized_result", "encryption_key_id", "result_digest");
    }
    assertThatCode(() -> migration.queryForObject(
        "SELECT core.purge_expired_ai_executions(400)", Integer.class))
        .as("Contract A's own purge still works")
        .doesNotThrowAnyException();
  }

  // -- helpers -------------------------------------------------------------------------------------

  private JdbcTemplate jdbc(String user, String password) {
    return new JdbcTemplate(new DriverManagerDataSource(databaseUrl, user, password));
  }

  /**
   * Every principal holding any privilege on a table, read from the ACL itself.
   *
   * <p>Not {@code information_schema.role_table_grants}: that view is filtered to grants the
   * querying role is party to, so asking it as the runtime role can only ever return the runtime
   * role — an assertion that would pass no matter what anyone else had been granted. The first
   * draft of this test did exactly that and passed for that reason. {@code pg_class.relacl} is
   * unfiltered, and shows {@code PUBLIC}, which the view renders as an ordinary grantee name.
   */
  private List<String> granteesOn(String table) {
    assertAclIsRecorded(table);
    return migration.queryForList("""
        SELECT DISTINCT CASE WHEN acl.grantee = 0 THEN 'PUBLIC'
                             ELSE pg_get_userbyid(acl.grantee) END
          FROM pg_class relation
          JOIN pg_namespace space ON space.oid = relation.relnamespace
          CROSS JOIN LATERAL aclexplode(relation.relacl) acl
         WHERE space.nspname = 'core' AND relation.relname = ?
        """, String.class, table);
  }

  private List<String> privilegesOn(String table, String grantee) {
    assertAclIsRecorded(table);
    return migration.queryForList("""
        SELECT acl.privilege_type
          FROM pg_class relation
          JOIN pg_namespace space ON space.oid = relation.relnamespace
          CROSS JOIN LATERAL aclexplode(relation.relacl) acl
         WHERE space.nspname = 'core' AND relation.relname = ?
           AND CASE WHEN acl.grantee = 0 THEN 'PUBLIC'
                    ELSE pg_get_userbyid(acl.grantee) END = ?
        """, String.class, table, grantee);
  }

  /**
   * A null {@code relacl} means "defaults only" and would make every emptiness assertion above
   * vacuously true. V037 issues explicit revokes and grants on all four tables, so the ACL is
   * populated; checking that first is what stops "no grants found" from meaning "no ACL read".
   */
  private void assertAclIsRecorded(String table) {
    Boolean recorded = migration.queryForObject("""
        SELECT relation.relacl IS NOT NULL
          FROM pg_class relation
          JOIN pg_namespace space ON space.oid = relation.relnamespace
         WHERE space.nspname = 'core' AND relation.relname = ?
        """, Boolean.class, table);
    assertThat(recorded).as("core.%s must carry an explicit ACL", table).isTrue();
  }

  private List<String> functionExecutors() {
    return migration.queryForList("""
        SELECT DISTINCT CASE WHEN acl.grantee = 0 THEN 'PUBLIC'
                             ELSE pg_get_userbyid(acl.grantee) END
          FROM pg_proc routine
          JOIN pg_namespace space ON space.oid = routine.pronamespace
          CROSS JOIN LATERAL aclexplode(routine.proacl) acl
         WHERE space.nspname = 'core'
           AND routine.proname IN ('adopt_ai_execution_result',
                                   'purge_expired_ai_execution_results')
           AND acl.privilege_type = 'EXECUTE'
        """, String.class);
  }

  private List<String> ledgerStates(String requestId) {
    return runtime.queryForList(
        "SELECT to_state FROM core.ai_execution_transition WHERE request_id = ?",
        String.class, requestId);
  }

  private int purgeLedgerCount(String requestId, String state) {
    Integer count = runtime.queryForObject("""
        SELECT count(*) FROM core.ai_execution_transition
         WHERE request_id = ? AND to_state = ?
        """, Integer.class, requestId, state);
    return count == null ? 0 : count;
  }

  private void seedExecution(String requestId, String state, int ageDays) {
    boolean terminal = List.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN_TERMINAL")
        .contains(state);
    migration.update("""
        INSERT INTO core.ai_provider_execution
          (request_id, provider, model, model_route, idempotency_key, custom_id,
           provider_execution_id, submit_fence, state, input_tokens, output_tokens,
           estimated_cost_usd, admitted_at, submitted_at, terminal_at)
        VALUES (?, 'anthropic', 'claude-sonnet-5', 'diagnostic', ?, ?, ?, 1, ?, 16, 4, 0.00003600,
                CURRENT_TIMESTAMP - make_interval(days => ?),
                CURRENT_TIMESTAMP - make_interval(days => ?),
                CASE WHEN ? THEN CURRENT_TIMESTAMP - make_interval(days => ?) ELSE NULL END)
        """,
        requestId, "idem-" + requestId, "custom-" + requestId,
        "msgbatch_" + requestId, state, ageDays, ageDays, terminal, ageDays);
    migration.update("""
        INSERT INTO core.ai_execution_transition (request_id, to_state, actor, reason)
        VALUES (?, 'RESULT_STORED', 'RECONCILER', 'RESULT_RETRIEVED')
        """, requestId);
  }

  /**
   * Inserts a result aged into the past.
   *
   * <p>Written as the migration role rather than aged afterwards, because there is no UPDATE path
   * to age it with — the trigger and the grant both forbid one, which is the property under test.
   */
  private void seedAgedResult(String requestId, int ageDays) {
    insertSealed(requestId, ageDays, 30);
  }

  private void insertSealed(String requestId, int ageDays, int ceilingDays) {
    migration.update("""
        INSERT INTO core.ai_execution_result
          (request_id, provider_execution_id, normalized_result, encryption_key_id,
           result_digest, result_schema, stored_at, purge_after)
        VALUES (?, ?, ?, ?, repeat('c', 64), 'diagnostic-proposal.v1',
                CURRENT_TIMESTAMP - make_interval(days => ?),
                CURRENT_TIMESTAMP - make_interval(days => ?) + make_interval(days => ?))
        """,
        requestId, "msgbatch_seed", sealedEnvelope(requestId), KEY_V1,
        ageDays, ageDays, ceilingDays);
  }

  private byte[] sealedEnvelope(String requestId) {
    return new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider().with(KEY_V1).active(KEY_V1))
        .seal(proposal(requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8), requestId)
        .envelope();
  }

  /** A valid {@code diagnostic-proposal.v1} carrying the canary in its reason text. */
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
