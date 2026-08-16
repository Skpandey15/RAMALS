package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MVP-1 entry criterion 6: the AI workload identity cannot reach the platform's data.
 *
 * <p>This is the boundary itself, not a description of it. MVP-1 introduces a Python runtime, and
 * the constraint on it is built first — the same order MVP-0 used for its database invariants, which
 * were in place before the features that depend on them.
 *
 * <p>In a real environment {@code ramals_ai_runtime} cannot even open a session: {@code V015}
 * revokes CONNECT and the role is NOLOGIN with no password. These tests deliberately re-grant LOGIN
 * and CONNECT so the *object* privileges can be demonstrated positively. Asserting only "the
 * connection fails" would pass for uninteresting reasons — a typo in the password looks identical to
 * a revoked privilege. Proving 42501 on every table shows the denial is the privilege model working.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class AiRuntimeBoundaryIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String AI_USER = "ramals_ai_runtime";
  private static final String AI_PASSWORD = "m1-b1-ai-test";

  /** Every table the AI identity must never reach, across all three schemas. */
  private static final List<String> FORBIDDEN_TABLES = List.of(
      "core.learner", "core.assessment_attempt", "core.learner_skill_aggregate",
      "ledger.evidence", "ledger.mastery_snapshot", "ledger.decision_record",
      "audit.admin_activity", "audit.security_audit");

  private static String databaseUrl;
  private JdbcTemplate aiJdbc;

  @BeforeAll
  static void migrateAndEnableTestLogin() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    String adminUser = requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      // V002 grants to the runtime role, so both platform roles must exist before Flyway runs.
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
            -- Provisioned here the way infrastructure/docker/postgres-init provisions it, because
            -- ramals_core_migration deliberately lacks CREATEROLE and V015 only revokes privilege.
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
              CREATE ROLE ramals_ai_runtime NOLOGIN;
            END IF;
          END
          $$;
          """);
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", ramals_core_runtime");
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

    // Re-grant only what is needed to open a session, so the object-level denials can be observed.
    // Production keeps the posture V015 leaves behind: NOLOGIN, no password, no CONNECT.
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      statement.execute(
          "ALTER ROLE " + AI_USER + " WITH LOGIN PASSWORD '" + AI_PASSWORD + "'");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO " + AI_USER);
    }
  }

  private JdbcTemplate ai() {
    if (aiJdbc == null) {
      aiJdbc = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, AI_USER, AI_PASSWORD));
    }
    return aiJdbc;
  }

  @Test
  void aiRuntimeIdentityIsProvisioned() {
    Integer roles = new JdbcTemplate(
        new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD))
        .queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, AI_USER);
    assertThat(roles)
        .as("the AI workload identity must be provisioned for V015 to have anything to revoke")
        .isEqualTo(1);
  }

  @Test
  void aiRuntimeCannotReadAnyPlatformTable() {
    for (String table : FORBIDDEN_TABLES) {
      assertThatThrownBy(() -> ai().queryForObject("SELECT count(*) FROM " + table, Integer.class))
          .as("AI runtime must not read %s", table)
          .isInstanceOfSatisfying(DataAccessException.class,
              exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
    }
  }

  @Test
  void aiRuntimeCannotWriteAnyPlatformTable() {
    // Reading is the obvious risk; writing is the one that would corrupt the control. An agent that
    // can append to ledger.mastery_snapshot makes mastery no longer deterministic.
    for (String table : FORBIDDEN_TABLES) {
      assertThatThrownBy(() -> ai().execute("DELETE FROM " + table))
          .as("AI runtime must not write %s", table)
          .isInstanceOfSatisfying(DataAccessException.class,
              exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
    }
  }

  @Test
  void aiRuntimeHasNoSchemaUsage() {
    // The root of the denial: without USAGE on the schema, nothing inside it is addressable, so a
    // table added by a future migration is unreachable without anyone remembering to revoke it.
    for (String schema : List.of("core", "ledger", "audit")) {
      Boolean usable = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD))
          .queryForObject(
              "SELECT has_schema_privilege(?, ?, 'USAGE')", Boolean.class, AI_USER, schema);
      assertThat(usable).as("AI runtime must not hold USAGE on %s", schema).isFalse();
    }
  }

  @Test
  void aiRuntimeCannotCreateObjects() {
    assertThatThrownBy(() -> ai().execute("CREATE TABLE core.ai_side_channel (id int)"))
        .as("AI runtime must not create its own tables in platform schemas")
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
  }

  /** Assert on SQLSTATE, not message text: 42501 is insufficient_privilege regardless of wording. */
  private static String sqlState(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (var result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required environment variable: " + name);
    }
    return value;
  }
}
