package io.ramals.learningplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
@TestMethodOrder(OrderAnnotation.class)
class PostgresMigrationIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static String databaseUrl;

  @BeforeAll
  static void prepareRoles() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    String adminUser = requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl,
            adminUser,
            requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String databaseName = currentDatabase(statement);
      String quotedDatabase = statement.enquoteIdentifier(databaseName, true);
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
  }

  @Test
  @Order(1)
  void emptyDatabaseInstallsThenForwardUpgradeValidates() {
    Flyway baseline = configuration("classpath:db/migration")
        .target(MigrationVersion.fromVersion("1"))
        .load();
    assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);

    Flyway upgraded = configuration("classpath:db/migration", "classpath:db/upgrade").load();
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
    assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  @Order(2)
  void runtimeCannotExecuteDdlOrMutateLedger() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection runtime = DriverManager.getConnection(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
        Statement statement = runtime.createStatement()) {
      statement.executeUpdate("INSERT INTO ledger.privilege_probe (id, mastery_value) VALUES ('"
          + id + "', 0.750000)");

      assertThatThrownBy(() -> statement.execute("CREATE TABLE core.runtime_must_not_create (id UUID)"))
          .isInstanceOfSatisfying(SQLException.class, PostgresMigrationIntegrationTests::assertPermissionDenied);
      assertThatThrownBy(() -> statement.executeUpdate(
          "UPDATE core.flyway_schema_history SET description = 'tampered' WHERE installed_rank = 1"))
          .isInstanceOfSatisfying(SQLException.class, PostgresMigrationIntegrationTests::assertPermissionDenied);
      assertThatThrownBy(() -> statement.executeUpdate(
          "UPDATE ledger.privilege_probe SET mastery_value = 0.800000 WHERE id = '" + id + "'"))
          .isInstanceOfSatisfying(SQLException.class, PostgresMigrationIntegrationTests::assertPermissionDenied);
      assertThatThrownBy(() -> statement.executeUpdate(
          "DELETE FROM ledger.privilege_probe WHERE id = '" + id + "'"))
          .isInstanceOfSatisfying(SQLException.class, PostgresMigrationIntegrationTests::assertPermissionDenied);
    }
  }

  @Test
  @Order(3)
  void migrationRoleCanEvolveSchemaAndNumericUuidConventionsHold() throws SQLException {
    try (Connection migration = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = migration.createStatement()) {
      statement.execute("ALTER TABLE core.upgrade_probe ADD COLUMN migration_verified BOOLEAN NOT NULL DEFAULT TRUE");

      try (ResultSet columns = statement.executeQuery("""
          SELECT data_type, numeric_precision, numeric_scale
          FROM information_schema.columns
          WHERE table_schema = 'ledger'
            AND table_name = 'privilege_probe'
            AND column_name = 'mastery_value'
          """)) {
        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("data_type")).isEqualTo("numeric");
        assertThat(columns.getInt("numeric_precision")).isEqualTo(8);
        assertThat(columns.getInt("numeric_scale")).isEqualTo(6);
      }

      try (ResultSet forbiddenTypes = statement.executeQuery("""
          SELECT count(*)
          FROM information_schema.columns
          WHERE table_schema IN ('core', 'ledger', 'audit')
            AND data_type IN ('real', 'double precision')
          """)) {
        forbiddenTypes.next();
        assertThat(forbiddenTypes.getInt(1)).isZero();
      }
    }
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration configuration(String... locations) {
    return Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations(locations)
        .defaultSchema("core")
        .schemas("core", "ledger", "audit")
        .createSchemas(true)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false);
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

  private static void assertPermissionDenied(SQLException exception) {
    assertThat(exception.getSQLState()).isEqualTo("42501");
  }
}
