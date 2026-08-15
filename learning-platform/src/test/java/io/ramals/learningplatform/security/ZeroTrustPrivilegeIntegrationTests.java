package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * Zero Trust least-privilege verification for the runtime database identity. The runtime role may
 * only SELECT+INSERT immutable ledger/audit history, never UPDATE or DELETE it, never create schema
 * objects, and never tamper with the migration history. Privilege denial (42501) is checked before
 * any append-only trigger fires, so this exercises the privilege layer specifically.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ZeroTrustPrivilegeIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final List<String> APPEND_ONLY_TABLES = List.of(
      "ledger.evidence", "ledger.mastery_snapshot", "ledger.decision_record", "audit.admin_activity");

  private static String databaseUrl;
  private JdbcTemplate runtimeJdbc;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    String adminUser = requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      String quotedAdmin = statement.enquoteIdentifier(adminUser, true);
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
          END
          $$;
          """);
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + quotedAdmin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
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

  private JdbcTemplate runtime() {
    if (runtimeJdbc == null) {
      runtimeJdbc = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD));
    }
    return runtimeJdbc;
  }

  @Test
  void runtimeCannotMutateAppendOnlyLedgerOrAuditTables() {
    JdbcTemplate runtime = runtime();
    for (String table : APPEND_ONLY_TABLES) {
      assertThatThrownBy(() -> runtime.execute("DELETE FROM " + table))
          .as("DELETE from %s must be denied", table)
          .isInstanceOfSatisfying(DataAccessException.class,
              exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
    }
  }

  @Test
  void runtimeCannotCreateSchemaObjects() {
    JdbcTemplate runtime = runtime();
    assertThatThrownBy(() -> runtime.execute("CREATE TABLE core.zt_probe (id UUID)"))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
    assertThatThrownBy(() -> runtime.execute("CREATE TABLE ledger.zt_probe (id UUID)"))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
  }

  @Test
  void runtimeCannotTamperWithMigrationHistory() {
    JdbcTemplate runtime = runtime();
    assertThatThrownBy(() -> runtime.execute(
        "UPDATE core.flyway_schema_history SET description = 'tampered' WHERE installed_rank = 1"))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
  }

  private static String sqlState(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
    }
    return value;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery("SELECT current_database()")) {
      if (!result.next()) {
        throw new SQLException("PostgreSQL did not return current_database()");
      }
      return result.getString(1);
    }
  }
}
