package io.ramals.learningplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
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
    // 44 with V044 (learning journey). Asserting the count rather than merely that the upgrade
    // succeeds is what makes an accidentally unapplied migration visible -- it caught V029 the first
    // time it ran, V042 the first time this suite saw a real PostgreSQL, and V043 in CI.
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(44);
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
  @Order(8)
  void diagnosticDispatchPrivilegesRemainCompatibleWithTheCoreRuntimeContract()
      throws SQLException {
    try (Connection runtime =
            DriverManager.getConnection(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
        Statement statement = runtime.createStatement();
        ResultSet privileges =
            statement.executeQuery(
                """
                SELECT has_table_privilege(current_user, 'core.ai_execution_dispatch', 'SELECT'),
                       has_table_privilege(current_user, 'core.ai_execution_dispatch', 'INSERT'),
                       has_table_privilege(current_user, 'core.ai_execution_dispatch', 'UPDATE'),
                       has_table_privilege(current_user, 'core.ai_execution_dispatch', 'DELETE'),
                       has_table_privilege(current_user, 'core.ai_execution_dispatch', 'TRUNCATE')
                """)) {
      assertThat(privileges.next()).isTrue();
      assertThat(privileges.getBoolean(1)).isTrue();
      assertThat(privileges.getBoolean(2)).isTrue();
      assertThat(privileges.getBoolean(3)).isTrue();
      // V002 deliberately gives the core runtime DELETE on current and future core tables. V035
      // must remain additive so rolling back the application image does not narrow that established
      // role contract; the repository exposes no dispatch-row delete path.
      assertThat(privileges.getBoolean(4)).isTrue();
      assertThat(privileges.getBoolean(5)).isFalse();
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

  @Test
  @Order(7)
  void databaseAllowsExactlyOneTerminalExecutionEvent() throws SQLException {
    UUID requestId = UUID.randomUUID();
    String request = requestId.toString();
    String interaction = UUID.randomUUID().toString();
    String digest = "a".repeat(64);
    try (Connection runtime = DriverManager.getConnection(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
        Statement statement = runtime.createStatement()) {
      statement.executeUpdate("""
          INSERT INTO core.ai_execution_event
            (id, request_id, interaction_id, agent_type, contract_version, event_type,
             request_digest, occurred_at)
          VALUES ('01920000-0000-7000-8000-000000009991', '%s', '%s', 'ASSESSMENT', '1.0',
                  'STARTED', '%s', CURRENT_TIMESTAMP)
          """.formatted(request, interaction, digest));
      statement.executeUpdate("""
          INSERT INTO core.ai_execution_event
            (id, request_id, interaction_id, agent_type, contract_version, event_type,
             request_digest, occurred_at)
          VALUES ('01920000-0000-7000-8000-000000009992', '%s', '%s', 'ASSESSMENT', '1.0',
                  'SUCCEEDED', '%s', CURRENT_TIMESTAMP)
          """.formatted(request, interaction, digest));

      assertThatThrownBy(() -> statement.executeUpdate("""
          INSERT INTO core.ai_execution_event
            (id, request_id, interaction_id, agent_type, contract_version, event_type,
             error_code, request_digest, occurred_at)
          VALUES ('01920000-0000-7000-8000-000000009993', '%s', '%s', 'ASSESSMENT', '1.0',
                  'FAILED', 'AI_TIMEOUT', '%s', CURRENT_TIMESTAMP)
          """.formatted(request, interaction, digest)))
          .isInstanceOfSatisfying(SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));
    }
  }

  @Test
  @Order(4)
  void kafkaV1SeedIsPublishedVersionedAndDeterministic() throws SQLException {
    try (Connection runtime = DriverManager.getConnection(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
        Statement statement = runtime.createStatement()) {
      assertThat(count(statement, """
          SELECT count(*) FROM core.skill_version sv
          JOIN core.curriculum_version cv ON cv.id = sv.curriculum_version_id
          JOIN core.learning_domain d ON d.id = cv.domain_id
          WHERE d.code = 'KAFKA' AND cv.version_code = 'v1' AND cv.status = 'PUBLISHED'
          """)).isEqualTo(15);
      assertThat(count(statement, """
          SELECT count(*) FROM core.learning_objective objective
          JOIN core.skill_version sv ON sv.id = objective.skill_version_id
          WHERE sv.curriculum_version_id = '01900000-0000-7000-8000-000000000002'
          """)).isEqualTo(15);
      assertThat(count(statement, """
          SELECT count(*) FROM core.skill_prerequisite
          WHERE curriculum_version_id = '01900000-0000-7000-8000-000000000002'
          """)).isEqualTo(16);
    }
  }

  @Test
  @Order(5)
  void databaseRejectsCyclesDuplicateStableCodesAndPublishedMutation() throws SQLException {
    try (Connection migration = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = migration.createStatement()) {
      assertThatThrownBy(() -> statement.execute("""
          INSERT INTO core.skill (id, domain_id, stable_code)
          VALUES ('01900000-0000-7000-8000-000000009901',
                  '01900000-0000-7000-8000-000000000001', 'KAFKA_BROKER')
          """))
          .isInstanceOfSatisfying(SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));

      assertThatThrownBy(() -> statement.execute("""
          UPDATE core.skill_version SET title = 'Changed'
          WHERE id = '01900000-0000-7000-8000-000000000201'
          """))
          .isInstanceOfSatisfying(SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("55000"));

      assertThatThrownBy(() -> statement.execute("""
          UPDATE core.skill SET stable_code = 'KAFKA_BROKER_RENAMED'
          WHERE id = '01900000-0000-7000-8000-000000000101'
          """))
          .isInstanceOfSatisfying(SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("55000"));

      statement.execute("""
          INSERT INTO core.curriculum_version (id, domain_id, version_code)
          VALUES ('01900000-0000-7000-8000-000000009902',
                  '01900000-0000-7000-8000-000000000001', 'cycle-test')
          """);
      statement.execute("""
          INSERT INTO core.skill_version
            (id, skill_id, curriculum_version_id, title, description, difficulty,
             estimated_learning_minutes, display_order)
          VALUES
            ('01900000-0000-7000-8000-000000009903','01900000-0000-7000-8000-000000000101','01900000-0000-7000-8000-000000009902','A','A','FOUNDATIONAL',10,1),
            ('01900000-0000-7000-8000-000000009904','01900000-0000-7000-8000-000000000102','01900000-0000-7000-8000-000000009902','B','B','FOUNDATIONAL',10,2),
            ('01900000-0000-7000-8000-000000009905','01900000-0000-7000-8000-000000000103','01900000-0000-7000-8000-000000009902','C','C','FOUNDATIONAL',10,3)
          """);
      statement.execute("""
          INSERT INTO core.skill_prerequisite (curriculum_version_id, skill_id, prerequisite_skill_id)
          VALUES
            ('01900000-0000-7000-8000-000000009902','01900000-0000-7000-8000-000000000101','01900000-0000-7000-8000-000000000102'),
            ('01900000-0000-7000-8000-000000009902','01900000-0000-7000-8000-000000000102','01900000-0000-7000-8000-000000000103')
          """);
      assertThatThrownBy(() -> statement.execute("""
          INSERT INTO core.skill_prerequisite (curriculum_version_id, skill_id, prerequisite_skill_id)
          VALUES ('01900000-0000-7000-8000-000000009902',
                  '01900000-0000-7000-8000-000000000103',
                  '01900000-0000-7000-8000-000000000101')
          """))
          .isInstanceOfSatisfying(SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
    }
  }

  @Test
  @Order(6)
  void retiredHistoricalVersionRemainsQueryableWithoutNPlusOneReads() throws SQLException {
    try (Connection migration = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = migration.createStatement()) {
      statement.execute("""
          UPDATE core.curriculum_version SET status = 'RETIRED'
          WHERE id = '01900000-0000-7000-8000-000000000002'
          """);
    }

    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
    CurriculumGraph graph = new CurriculumRepository(new JdbcTemplate(dataSource))
        .findReadableGraph("KAFKA", "v1")
        .orElseThrow();
    new CurriculumGraphValidator().validate(graph);

    assertThat(graph.status()).isEqualTo("RETIRED");
    assertThat(graph.skills()).hasSize(15);
    assertThat(graph.skills().stream()
        .filter(skill -> skill.stableCode().equals("KAFKA_FAILURE_RECOVERY"))
        .findFirst().orElseThrow().prerequisiteSkillCodes())
        .containsExactly("KAFKA_ISR", "KAFKA_REBALANCING");
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration configuration(String... locations) {
    return Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations(locations)
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
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

  private static int count(Statement statement, String sql) throws SQLException {
    try (ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getInt(1);
    }
  }
}
