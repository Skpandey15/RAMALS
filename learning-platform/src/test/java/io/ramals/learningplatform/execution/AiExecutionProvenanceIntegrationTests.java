package io.ramals.learningplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The three properties M1-T13 asks of AI execution provenance, against real PostgreSQL.
 *
 * <p><b>Reconstruction.</b> A historical decision must be able to identify the AI activity that
 * accompanied it. The link is {@code interaction_id}, not a foreign key, and that is forced rather
 * than chosen: {@code ledger.decision_record} is append-only by trigger and is written <em>before</em>
 * the AI call, so a column pointing at a proposal could never be filled in afterwards. The
 * correlation contract already carries the same identifier through both writes.
 *
 * <p><b>Redaction.</b> Structural. There is no free-text column to hold a prompt, a learner's
 * context, a credential or a model's output, so nothing needs removing later.
 *
 * <p><b>Retention.</b> 400 days, with a purge an operator can run. MVP-1 has no scheduler, so a
 * function that can be tested is the honest form of the control.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class AiExecutionProvenanceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";

  private static String databaseUrl;
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
            ELSE
              ALTER ROLE ramals_core_runtime WITH LOGIN PASSWORD 'm0-t05-runtime-test';
            END IF;
            -- The AI identity must exist here or the privilege assertions below cannot run at all:
            -- has_table_privilege on an unknown role is an error, which would look like a failing
            -- boundary rather than a missing fixture.
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
              CREATE ROLE ramals_ai_runtime NOLOGIN;
            END IF;
          END
          $$;
          """);
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + admin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
    }

    Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for this integration test");
    }
    return value;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (var result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
    source.setDriverClassName("org.postgresql.Driver");
    jdbc = new JdbcTemplate(source);
  }

  private UUID insertExecution(String interactionId, String requestId, int ageDays) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, status,
           request_digest, started_at, completed_at)
        VALUES (?, ?, ?, 'ADAPTATION', '1.0', 'SUCCEEDED', repeat('a', 64),
                CURRENT_TIMESTAMP - make_interval(days => ?),
                CURRENT_TIMESTAMP - make_interval(days => ?))
        """, id, requestId, interactionId, ageDays, ageDays);
    return id;
  }

  // -- reconstruction ------------------------------------------------------------------------------

  @Test
  @DisplayName("a historical decision can identify the AI activity that accompanied it")
  void aDecisionCanFindItsAiExecutions() {
    String interactionId = "01920000-0000-7000-8000-" + UUID.randomUUID().toString().substring(24);
    insertExecution(interactionId, "request-" + UUID.randomUUID(), 0);
    insertExecution(interactionId, "request-" + UUID.randomUUID(), 0);
    insertExecution("01920000-0000-7000-8000-999999999999", "request-" + UUID.randomUUID(), 0);

    // The query an investigator runs, starting from a decision record's interaction id.
    List<String> found = jdbc.queryForList("""
        SELECT request_id FROM core.ai_execution WHERE interaction_id = ? ORDER BY started_at
        """, String.class, interactionId);

    assertThat(found).hasSize(2);
  }

  @Test
  @DisplayName("both sides of the link carry the same correlation column")
  void bothSidesShareTheCorrelationColumn() {
    // Structural rather than behavioural. The join is only reconstructable for as long as both
    // tables keep the column, and losing it on either side would break reconstruction silently --
    // the query would return nothing rather than fail.
    assertThat(columnsOf("core", "ai_execution")).contains("interaction_id");
    assertThat(columnsOf("ledger", "decision_record")).contains("interaction_id");
  }

  @Test
  @DisplayName("a decision record cannot be back-filled with a proposal link")
  void aDecisionRecordCannotBeUpdatedAfterTheFact() {
    // Why the link is correlation rather than a foreign key: the decision is written before the AI
    // call, and an append-only trigger refuses any later update. A nullable proposal column would
    // be permanently null and would read as "no AI was involved".
    //
    // Asserted on the trigger rather than by attempting an update, because an UPDATE matching no
    // rows never fires a row trigger -- the attempt would pass on an empty table and prove nothing.
    Integer triggers = jdbc.queryForObject("""
        SELECT count(*) FROM pg_trigger
         WHERE tgrelid = 'ledger.decision_record'::regclass AND NOT tgisinternal
        """, Integer.class);

    assertThat(triggers).isGreaterThan(0);
  }

  // -- redaction -----------------------------------------------------------------------------------

  @Test
  @DisplayName("no column can hold a prompt, a learner's context or a model's output")
  void thereIsNothingToRedact() {
    for (String table : List.of("ai_execution", "ai_execution_event")) {
      List<String> unbounded = jdbc.queryForList("""
          SELECT column_name FROM information_schema.columns
           WHERE table_schema = 'core' AND table_name = ?
             AND (data_type = 'text' OR character_maximum_length > 256)
          """, String.class, table);

      // Bounded metadata only. A TEXT column, or a very wide one, is where free-form model output
      // would end up -- and redacting after the fact is a weaker control than having nowhere to put
      // it in the first place.
      assertThat(unbounded).as("%s", table).isEmpty();
    }
  }

  @Test
  @DisplayName("the digest columns hold digests, not content")
  void digestsAreDigests() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, status,
           request_digest, started_at, completed_at)
        VALUES (?, ?, ?, 'TUTOR', '1.0', 'SUCCEEDED', 'explain kafka partitions to me',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, UUID.randomUUID(), "request-" + UUID.randomUUID(), "interaction-1"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  // -- retention -----------------------------------------------------------------------------------

  @Test
  @DisplayName("the purge removes expired provenance and keeps the rest")
  void thePurgeRemovesOnlyExpiredRows() {
    UUID expired = insertExecution("interaction-old", "request-" + UUID.randomUUID(), 500);
    UUID kept = insertExecution("interaction-new", "request-" + UUID.randomUUID(), 10);

    Integer purged =
        jdbc.queryForObject("SELECT core.purge_expired_ai_executions(400)", Integer.class);

    // Asserted on these two rows rather than on the total. Sibling tests share this database and
    // also insert expired rows, so a global count would couple this test to their execution order.
    assertThat(purged).isGreaterThanOrEqualTo(1);
    assertThat(exists(expired)).isFalse();
    assertThat(exists(kept)).as("inside the window, so still reconstructable").isTrue();
  }

  @Test
  @DisplayName("a retention window that would delete everything is refused")
  void aZeroRetentionWindowIsRefused() {
    UUID today = insertExecution("interaction-today", "request-" + UUID.randomUUID(), 0);

    // Not recoverable from an append-only table, and never what an operator means.
    assertThatThrownBy(() -> jdbc.queryForObject(
        "SELECT core.purge_expired_ai_executions(0)", Integer.class))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    assertThat(exists(today)).isTrue();
  }

  @Test
  @DisplayName("a recent execution still cannot be deleted or rewritten")
  void immutabilityStillHoldsInsideTheWindow() {
    UUID recent = insertExecution("interaction-recent", "request-" + UUID.randomUUID(), 5);

    // The distinction retention rests on. History expires; it is not erased on request. Without
    // this, making the purge possible would have quietly made "delete yesterday's bad execution"
    // possible too, which is the property the append-only trigger existed to prevent.
    assertThatThrownBy(() ->
        jdbc.update("DELETE FROM core.ai_execution WHERE id = ?", recent))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() ->
        jdbc.update("UPDATE core.ai_execution SET status = 'FAILED' WHERE id = ?", recent))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    assertThat(exists(recent)).isTrue();
  }

  @Test
  @DisplayName("an expired row cannot be rewritten either, only removed")
  void expiredRowsAreStillNotRewritable() {
    UUID expired = insertExecution("interaction-expired", "request-" + UUID.randomUUID(), 500);

    // Expiry permits deletion, not editing. An UPDATE past the window would let a historical record
    // be changed rather than aged out, which is a different thing entirely.
    assertThatThrownBy(() ->
        jdbc.update("UPDATE core.ai_execution SET status = 'FAILED' WHERE id = ?", expired))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  @DisplayName("the retention policy is recorded where an operator would look")
  void theRetentionPolicyIsDiscoverable() {
    // A policy that lives only in an ADR is one a database administrator will not find.
    String comment = jdbc.queryForObject(
        "SELECT obj_description('core.ai_execution'::regclass)", String.class);

    assertThat(comment).contains("400 days");
    assertThat(comment).contains("interaction_id");
  }

  // -- the AI plane gets nothing --------------------------------------------------------------------

  @Test
  @DisplayName("the AI runtime role holds no privilege on execution provenance")
  void theAiRuntimeRoleHoldsNothing() {
    // The MVP-1 plan line says to grant this role DML here. M1-ADR-005 decided afterwards that
    // Spring owns the table and the AI plane reaches nothing by SQL, so the plan line is superseded
    // and this asserts the decision that won.
    for (String table : List.of("core.ai_execution", "core.ai_execution_event")) {
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        Boolean granted = jdbc.queryForObject("""
            SELECT has_table_privilege('ramals_ai_runtime', ?, ?)
            """, Boolean.class, table, privilege);
        assertThat(granted).as("%s on %s", privilege, table).isFalse();
      }
    }
  }

  @Test
  @DisplayName("Spring's runtime role can write provenance, so the boundary is not merely closed")
  void theCoreRuntimeRoleCanWrite() {
    // The converse. A revoke that denied everyone would satisfy the test above and record nothing.
    assertThat(jdbc.queryForObject(
        "SELECT has_table_privilege('ramals_core_runtime', 'core.ai_execution', 'INSERT')",
        Boolean.class)).isTrue();
  }

  private boolean exists(UUID id) {
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM core.ai_execution WHERE id = ?", Integer.class, id);
    return count != null && count > 0;
  }

  private List<String> columnsOf(String schema, String table) {
    return jdbc.queryForList("""
        SELECT column_name FROM information_schema.columns
         WHERE table_schema = ? AND table_name = ?
        """, String.class, schema, table);
  }

  @Test
  @DisplayName("an execution row can actually be written to PostgreSQL")
  void executionRowsAreWritableAgainstPostgres() {
    // The test this class was missing. Twelve tests asserted the shape of the table, its
    // constraints, its retention and its redaction -- and none wrote a row through the repository,
    // so nothing noticed that every write failed against a real database.
    //
    // PostgreSQL's JDBC driver refuses a java.time.Instant parameter outright: "Can't infer the SQL
    // type to use for an instance of java.time.Instant". H2, which the unit tests use, accepts it.
    // M1-T18 found it on a deployed candidate, where the adaptation comparison dispatched, the row
    // could not be written, and the failure-recording path failed the same way -- so nothing was
    // left behind to notice either.
    AiExecutionRepository repository = new AiExecutionRepository(jdbc, tools.jackson.databind.json.JsonMapper.builder().build());
    Instant startedAt = Instant.now().minusMillis(120);
    Instant completedAt = Instant.now();

    AiExecution failure = repository.insertFailure(
        envelope("request-fail-1"), "ADAPTATION", "AI_UNAVAILABLE", startedAt, completedAt);

    assertThat(failure).as("a failed execution must be recordable").isNotNull();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM core.ai_execution WHERE request_id = ?", Integer.class,
        "request-fail-1"))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT started_at IS NOT NULL AND completed_at IS NOT NULL FROM core.ai_execution "
            + "WHERE request_id = ?", Boolean.class, "request-fail-1"))
        .as("the timestamps must survive the round trip, not merely the insert")
        .isTrue();
  }

  private static AiRequestEnvelope envelope(String requestId) {
    return new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION, "01a02500-0000-7000-8000-000000000001", requestId,
        new LearnerRef(UUID.randomUUID().toString(), "en"),
        new LearningContext("SKILL-1", null, null, null, null),
        null, null,
        new Constraints(InteractionClass.INTERACTIVE_AI, 12000, null, null, null),
        "ADAPT");
  }
}
