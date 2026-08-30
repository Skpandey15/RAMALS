package io.ramals.learningplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshotDraft;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises progression against real PostgreSQL over the seeded Kafka graph: the retention schedule
 * is maintained by the mastery-snapshot trigger, dependents lock until prerequisites are mastered, a
 * regressed prerequisite re-locks not-yet-mastered dependents while already-mastered dependents and
 * the immutable snapshot history are preserved, and a due retention surfaces RETENTION_DUE.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ProgressionPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID CURRICULUM_VERSION = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID TOPIC_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000102");
  private static final String INTERACTION_ID = "01920000-0000-7000-8000-0000000000e1";

  private static String databaseUrl;
  private LearnerRepository learners;
  private MasteryRepository masteryRepository;
  private ProgressionService progressionService;
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
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }

    org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private void wire() {
    if (progressionService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      learners = new LearnerRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      CurriculumService curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      progressionService = new ProgressionService(
          curriculumService, new LearnerService(learners),
          new ProgressionRepository(runtimeJdbc), new ProgressionPolicy());
    }
  }

  private void insertSnapshot(UUID learnerId, UUID skillId, int version, MasteryStatus status, String score) {
    masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, CURRICULUM_VERSION, version, new BigDecimal(score), status,
        new BigDecimal("0.8000"), new BigDecimal("0.5000"), new BigDecimal("0.7500"), 5, 5,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V1", INTERACTION_ID));
  }

  private ProgressionState stateOf(String subject, UUID skillId) {
    return progressionService.progression(subject, "KAFKA", "v1").stream()
        .filter(skill -> skill.skillId().equals(skillId)).findFirst().orElseThrow().state();
  }

  private String reasonOf(String subject, UUID skillId) {
    return progressionService.progression(subject, "KAFKA", "v1").stream()
        .filter(skill -> skill.skillId().equals(skillId)).findFirst().orElseThrow().reasonCode();
  }

  @Test
  void masterySnapshotTriggerMaintainsTheRetentionSchedule() {
    wire();
    UUID learnerId = learners.provisionForSubject("prog-retention").id();
    insertSnapshot(learnerId, BROKER_SKILL, 1, MasteryStatus.MASTERED, "0.9000");

    Long scheduled = runtimeJdbc.queryForObject("""
        SELECT count(*) FROM core.skill_retention
        WHERE learner_id = ? AND skill_id = ? AND last_success_at IS NOT NULL
          AND retention_due_at IS NOT NULL
        """, Long.class, learnerId, BROKER_SKILL);
    assertThat(scheduled).isEqualTo(1);

    // a later non-mastered snapshot refreshes evidence time but keeps the last success
    insertSnapshot(learnerId, BROKER_SKILL, 2, MasteryStatus.NEEDS_PRACTICE, "0.6000");
    Long retained = runtimeJdbc.queryForObject("""
        SELECT count(*) FROM core.skill_retention
        WHERE learner_id = ? AND skill_id = ? AND last_success_at IS NOT NULL
        """, Long.class, learnerId, BROKER_SKILL);
    assertThat(retained).isEqualTo(1);
  }

  @Test
  void dependentSkillLocksUntilPrerequisiteIsMastered() {
    wire();
    UUID learnerId = learners.provisionForSubject("prog-prereq").id();

    assertThat(stateOf("prog-prereq", BROKER_SKILL)).isEqualTo(ProgressionState.ELIGIBLE);
    assertThat(stateOf("prog-prereq", TOPIC_SKILL)).isEqualTo(ProgressionState.LOCKED);

    insertSnapshot(learnerId, BROKER_SKILL, 1, MasteryStatus.MASTERED, "0.9000");
    assertThat(stateOf("prog-prereq", TOPIC_SKILL)).isEqualTo(ProgressionState.ELIGIBLE);
  }

  @Test
  void regressionRelocksNotYetMasteredDependentsWithoutErasingHistory() {
    wire();
    UUID learnerId = learners.provisionForSubject("prog-regress").id();
    insertSnapshot(learnerId, BROKER_SKILL, 1, MasteryStatus.MASTERED, "0.9000");
    assertThat(stateOf("prog-regress", TOPIC_SKILL)).isEqualTo(ProgressionState.ELIGIBLE);

    insertSnapshot(learnerId, BROKER_SKILL, 2, MasteryStatus.NEEDS_PRACTICE, "0.6000");
    assertThat(stateOf("prog-regress", BROKER_SKILL)).isEqualTo(ProgressionState.NEEDS_PRACTICE);
    assertThat(stateOf("prog-regress", TOPIC_SKILL)).isEqualTo(ProgressionState.LOCKED);
    assertThat(reasonOf("prog-regress", TOPIC_SKILL)).isEqualTo("REMEDIATION_REQUIRED");

    // the historical MASTERED snapshot is preserved, not erased
    Long masteredHistory = runtimeJdbc.queryForObject("""
        SELECT count(*) FROM ledger.mastery_snapshot
        WHERE learner_id = ? AND skill_id = ? AND mastery_status = 'MASTERED'
        """, Long.class, learnerId, BROKER_SKILL);
    assertThat(masteredHistory).isEqualTo(1);
  }

  @Test
  void masteredDependentStaysMasteredWhenPrerequisiteRegresses() {
    wire();
    UUID learnerId = learners.provisionForSubject("prog-history").id();
    insertSnapshot(learnerId, BROKER_SKILL, 1, MasteryStatus.MASTERED, "0.9000");
    insertSnapshot(learnerId, TOPIC_SKILL, 1, MasteryStatus.MASTERED, "0.9000");

    insertSnapshot(learnerId, BROKER_SKILL, 2, MasteryStatus.NEEDS_PRACTICE, "0.6000");
    assertThat(stateOf("prog-history", BROKER_SKILL)).isEqualTo(ProgressionState.NEEDS_PRACTICE);
    assertThat(stateOf("prog-history", TOPIC_SKILL)).isEqualTo(ProgressionState.MASTERED);
  }

  @Test
  void dueRetentionSurfacesRetentionDueState() {
    wire();
    UUID learnerId = learners.provisionForSubject("prog-due").id();
    insertSnapshot(learnerId, BROKER_SKILL, 1, MasteryStatus.MASTERED, "0.9000");

    runtimeJdbc.update("""
        UPDATE core.skill_retention SET retention_due_at = ?
        WHERE learner_id = ? AND skill_id = ? AND curriculum_version_id = ?
        """, OffsetDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC),
        learnerId, BROKER_SKILL, CURRICULUM_VERSION);

    assertThat(stateOf("prog-due", BROKER_SKILL)).isEqualTo(ProgressionState.RETENTION_DUE);
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
