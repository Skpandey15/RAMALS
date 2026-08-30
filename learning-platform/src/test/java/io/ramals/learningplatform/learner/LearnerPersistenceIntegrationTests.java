package io.ramals.learningplatform.learner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises the learner domain against real PostgreSQL as the least-privileged runtime role, so
 * JIT provisioning, goal upsert idempotency, and cross-learner isolation are verified with the
 * production storage engine and grants rather than an in-memory substitute.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class LearnerPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static String databaseUrl;
  private LearnerRepository repository;
  private JdbcTemplate runtimeJdbc;

  @BeforeAll
  static void migrateAsRuntimeAndMigrationRoles() throws SQLException {
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
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }

    Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private LearnerRepository repository() {
    if (repository == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      repository = new LearnerRepository(runtimeJdbc);
    }
    return repository;
  }

  @Test
  void provisioningIsIdempotentPerSubject() {
    LearnerRepository learners = repository();
    Learner first = learners.provisionForSubject("prov-idem");
    Learner second = learners.provisionForSubject("prov-idem");

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.status()).isEqualTo("ACTIVE");
    Integer rows = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.learner WHERE subject = ?", Integer.class, "prov-idem");
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void distinctSubjectsReceiveDistinctLearners() {
    LearnerRepository learners = repository();
    Learner alpha = learners.provisionForSubject("dist-alpha");
    Learner beta = learners.provisionForSubject("dist-beta");

    assertThat(alpha.id()).isNotEqualTo(beta.id());
    assertThat(learners.findGoal(alpha.id())).isEmpty();
  }

  @Test
  void onlyActiveLearningDomainsResolve() {
    LearnerRepository learners = repository();
    assertThat(learners.findActiveDomainId("KAFKA")).isPresent();
    assertThat(learners.findActiveDomainId("DOES_NOT_EXIST")).isEmpty();
  }

  @Test
  void goalUpsertInsertsThenReplacesInPlace() {
    LearnerRepository learners = repository();
    Learner learner = learners.provisionForSubject("goal-upsert");
    UUID kafka = learners.findActiveDomainId("KAFKA").orElseThrow();

    LearnerGoal inserted = learners.upsertGoal(learner.id(), kafka, new BigDecimal("0.9000"), null);
    assertThat(inserted.targetDomainCode()).isEqualTo("KAFKA");
    assertThat(inserted.targetProficiency()).isEqualByComparingTo("0.9000");
    assertThat(inserted.targetDate()).isNull();

    LocalDate target = LocalDate.parse("2027-01-31");
    LearnerGoal replaced = learners.upsertGoal(learner.id(), kafka, new BigDecimal("0.8500"), target);
    assertThat(replaced.targetProficiency()).isEqualByComparingTo("0.8500");
    assertThat(replaced.targetDate()).isEqualTo(target);
    assertThat(replaced.updatedAt()).isAfterOrEqualTo(replaced.createdAt());

    Integer rows = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.learner_goal WHERE learner_id = ?", Integer.class, learner.id());
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void goalsAreIsolatedPerLearner() {
    LearnerRepository learners = repository();
    Learner owner = learners.provisionForSubject("iso-owner");
    Learner other = learners.provisionForSubject("iso-other");
    UUID kafka = learners.findActiveDomainId("KAFKA").orElseThrow();

    learners.upsertGoal(owner.id(), kafka, new BigDecimal("0.8000"), null);

    assertThat(learners.findGoal(owner.id())).isPresent();
    assertThat(learners.findGoal(other.id())).isEmpty();
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
