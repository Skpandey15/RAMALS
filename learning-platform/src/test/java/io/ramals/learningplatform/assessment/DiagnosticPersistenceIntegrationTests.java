package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies retry-safe diagnostic attempt creation against real PostgreSQL as the runtime role: the
 * same idempotency key returns one logical attempt, the one-active-attempt invariant holds under a
 * competing writer, attempts are version-pinned, and cross-learner reads are denied.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static String databaseUrl;
  private AssessmentRepository assessments;
  private LearnerRepository learners;
  private DiagnosticService diagnostics;
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

  private void wire() {
    if (diagnostics == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      assessments = new AssessmentRepository(runtimeJdbc, JsonMapper.builder().build());
      learners = new LearnerRepository(runtimeJdbc);
      diagnostics = new DiagnosticService(assessments, new LearnerService(learners));
    }
  }

  @Test
  void seedDiagnosticIsPublishedVersionPinnedAndAnswerKeyFree() {
    wire();
    ResolvedDiagnostic diagnostic = assessments.findPublishedDiagnostic("KAFKA").orElseThrow();
    assertThat(diagnostic.status()).isEqualTo("PUBLISHED");
    assertThat(diagnostic.assessmentCode()).isEqualTo("KAFKA_DIAGNOSTIC");
    assertThat(diagnostic.versionCode()).isEqualTo("v1");
    assertThat(assessments.findItems(diagnostic.assessmentVersionId())).hasSize(5);
  }

  @Test
  void sameIdempotencyKeyReturnsOneLogicalAttempt() {
    wire();
    AttemptCreation first = diagnostics.createAttempt("pg-same-key", "kafka", "key-1");
    AttemptCreation replay = diagnostics.createAttempt("pg-same-key", "kafka", "key-1");

    assertThat(first.created()).isTrue();
    assertThat(replay.created()).isFalse();
    assertThat(replay.attempt().id()).isEqualTo(first.attempt().id());

    Learner learner = learners.findBySubject("pg-same-key").orElseThrow();
    Integer rows = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_attempt WHERE learner_id = ?",
        Integer.class, learner.id());
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void oneActiveAttemptInvariantHoldsAcrossKeysAndCompetingWriters() {
    wire();
    AttemptCreation active = diagnostics.createAttempt("pg-one-active", "kafka", "key-1");
    AttemptCreation reused = diagnostics.createAttempt("pg-one-active", "kafka", "key-2");
    assertThat(reused.attempt().id()).isEqualTo(active.attempt().id());

    Learner learner = learners.findBySubject("pg-one-active").orElseThrow();
    UUID versionId = active.attempt().assessmentVersionId();
    assertThatThrownBy(() -> assessments.insertAttempt(learner.id(), versionId, "key-3"))
        .isInstanceOf(DuplicateKeyException.class);

    Integer active_ = runtimeJdbc.queryForObject("""
        SELECT count(*) FROM core.assessment_attempt
        WHERE learner_id = ? AND status = 'IN_PROGRESS'
        """, Integer.class, learner.id());
    assertThat(active_).isEqualTo(1);
  }

  @Test
  void attemptIsPinnedToThePublishedAssessmentVersion() {
    wire();
    ResolvedDiagnostic diagnostic = assessments.findPublishedDiagnostic("KAFKA").orElseThrow();
    AttemptCreation creation = diagnostics.createAttempt("pg-pinning", "kafka", "key-1");
    assertThat(creation.attempt().assessmentVersionId()).isEqualTo(diagnostic.assessmentVersionId());
  }

  @Test
  void attemptDetailIsOwnershipScoped() {
    wire();
    AttemptCreation creation = diagnostics.createAttempt("pg-owner", "kafka", "key-1");
    String attemptId = creation.attempt().id().toString();

    AttemptDetail detail = diagnostics.getAttempt("pg-owner", "kafka", attemptId);
    assertThat(detail.items()).hasSize(5);

    diagnostics.createAttempt("pg-intruder", "kafka", "key-9");
    assertThatThrownBy(() -> diagnostics.getAttempt("pg-intruder", "kafka", attemptId))
        .isInstanceOf(AttemptNotFoundException.class);
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

  @Test
  void attemptRecordsTheInteractionThatCreatedIt() {
    // Master Plan §8: an attempt must be correlatable back to the logical interaction in SQL, not
    // only through the application log, which has a retention horizon.
    wire();
    String interactionId = "01920000-0000-7000-8000-0000000000ab";
    MDC.put("interactionId", interactionId);
    try {
      AttemptCreation created = diagnostics.createAttempt("pg-interaction", "kafka", "key-i1");
      String stored = runtimeJdbc.queryForObject(
          "SELECT interaction_id FROM core.assessment_attempt WHERE id = ?",
          String.class, created.attempt().id());
      assertThat(stored).isEqualTo(interactionId);
    } finally {
      MDC.remove("interactionId");
    }
  }

}
