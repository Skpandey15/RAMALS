package io.ramals.learningplatform.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasterySnapshotDraft;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link GapDiagnosisService} against real PostgreSQL and the real seeded Kafka curriculum -- the
 * database-orchestration half {@link GapDiagnosisClassifierTests} cannot exercise: resolving the
 * curriculum graph, reading a learner's real {@code ledger.mastery_snapshot} rows, and the
 * unknown-learner and no-evidence-yet edge cases.
 *
 * <p>The three-level chain and the diamond re-convergence are proven here against the curriculum's
 * own real prerequisite edges (V003), not just a synthetic graph: BROKER -> TOPIC -> PARTITION is a
 * real chain, and PARTITION is the real shared ancestor of KAFKA_FAILURE_RECOVERY's two
 * prerequisites (ISR and REBALANCING), by way of REPLICATION and CONSUMER_GROUPS respectively.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class GapDiagnosisServicePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID TOPIC = UUID.fromString("01900000-0000-7000-8000-000000000102");
  private static final UUID PARTITION = UUID.fromString("01900000-0000-7000-8000-000000000103");
  private static final UUID CONSUMER_GROUPS = UUID.fromString("01900000-0000-7000-8000-000000000109");
  private static final UUID REBALANCING = UUID.fromString("01900000-0000-7000-8000-000000000112");
  private static final UUID REPLICATION = UUID.fromString("01900000-0000-7000-8000-000000000113");
  private static final UUID ISR = UUID.fromString("01900000-0000-7000-8000-000000000114");
  private static final UUID FAILURE_RECOVERY = UUID.fromString("01900000-0000-7000-8000-000000000115");

  private static String databaseUrl;
  private LearnerRepository learners;
  private MasteryRepository masteryRepository;
  private GapDiagnosisService diagnosis;

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

  @Test
  void unknownLearnerYieldsAnEmptyReport() {
    wire();
    GapDiagnosisReport report = diagnosis.diagnose("gap-unknown-learner", "KAFKA", "v1");

    assertThat(report.skills()).isEmpty();
  }

  @Test
  void aLearnerWithNoEvidenceAtAllReadsInsufficientEvidenceAcrossEverySeededSkill() {
    wire();
    learners.provisionForSubject("gap-no-evidence");

    GapDiagnosisReport report = diagnosis.diagnose("gap-no-evidence", "KAFKA", "v1");

    assertThat(report.learnerId()).isNotNull();
    assertThat(report.skills()).hasSize(15); // every skill V003 seeds for KAFKA
    assertThat(report.skills())
        .extracting(SkillGapDiagnosis::classification)
        .containsOnly(GapClassification.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void aRealThreeLevelChainNamesBrokerNotJustTopicAsPartitionsRootCause() {
    wire();
    Learner learner = learners.provisionForSubject("gap-real-chain");
    snapshot(learner.id(), BROKER, MasteryStatus.NEEDS_RETEACH);
    snapshot(learner.id(), TOPIC, MasteryStatus.NEEDS_PRACTICE);
    snapshot(learner.id(), PARTITION, MasteryStatus.DEVELOPING);

    GapDiagnosisReport report = diagnosis.diagnose("gap-real-chain", "KAFKA", "v1");

    SkillGapDiagnosis partition = diagnosisOf(report, "KAFKA_PARTITION");
    assertThat(partition.classification()).isEqualTo(GapClassification.PREREQUISITE_GAP);
    assertThat(partition.weakPrerequisiteSkillCodes()).containsExactly("KAFKA_TOPIC");
    assertThat(partition.candidateRootCauseSkillCodes()).containsExactly("KAFKA_BROKER");
  }

  @Test
  void theRealDiamondBehindFailureRecoveryConvergesOnPartitionAsTheSingleRoot() {
    wire();
    // KAFKA_FAILURE_RECOVERY depends on KAFKA_ISR and KAFKA_REBALANCING, which trace back through
    // two different paths -- REPLICATION and CONSUMER_GROUPS -- to the same shared ancestor,
    // KAFKA_PARTITION. TOPIC and BROKER are left with no snapshot at all (unknown, not weak), so
    // PARTITION -- not TOPIC or BROKER -- is where the weak chain actually stops.
    Learner learner = learnerFor("gap-real-diamond");
    snapshot(learner.id(), PARTITION, MasteryStatus.NEEDS_RETEACH);
    snapshot(learner.id(), REPLICATION, MasteryStatus.NEEDS_PRACTICE);
    snapshot(learner.id(), ISR, MasteryStatus.DEVELOPING);
    snapshot(learner.id(), CONSUMER_GROUPS, MasteryStatus.NEEDS_PRACTICE);
    snapshot(learner.id(), REBALANCING, MasteryStatus.DEVELOPING);
    snapshot(learner.id(), FAILURE_RECOVERY, MasteryStatus.DEVELOPING);

    GapDiagnosisReport report = diagnosis.diagnose("gap-real-diamond", "KAFKA", "v1");

    SkillGapDiagnosis failureRecovery = diagnosisOf(report, "KAFKA_FAILURE_RECOVERY");
    assertThat(failureRecovery.classification()).isEqualTo(GapClassification.PREREQUISITE_GAP);
    assertThat(failureRecovery.weakPrerequisiteSkillCodes())
        .containsExactlyInAnyOrder("KAFKA_ISR", "KAFKA_REBALANCING");
    // Deduplicated to the one true shared root, not reported twice and not misattributed to either
    // intermediate skill.
    assertThat(failureRecovery.candidateRootCauseSkillCodes()).containsExactly("KAFKA_PARTITION");
  }

  @Test
  void aSkillWithAllPrerequisitesMasteredIsAnIndependentGapNotInherited() {
    Learner learner = learnerFor("gap-independent");
    snapshot(learner.id(), BROKER, MasteryStatus.MASTERED);
    snapshot(learner.id(), TOPIC, MasteryStatus.NEEDS_PRACTICE);

    GapDiagnosisReport report = diagnosis.diagnose("gap-independent", "KAFKA", "v1");

    SkillGapDiagnosis topic = diagnosisOf(report, "KAFKA_TOPIC");
    assertThat(topic.classification()).isEqualTo(GapClassification.INDEPENDENT_GAP);
    assertThat(topic.candidateRootCauseSkillCodes()).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private Learner learnerFor(String subject) {
    wire();
    return learners.provisionForSubject(subject);
  }

  private void snapshot(UUID learnerId, UUID skillId, MasteryStatus status) {
    masteryRepository.ensureAggregate(learnerId, skillId, CURRICULUM);
    masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, CURRICULUM, 1,
        new BigDecimal("0.5000"), status, new BigDecimal("0.7500"),
        new BigDecimal("0.9000"), new BigDecimal("0.7500"), 4, 8,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2", "MASTERY_STATUS_POLICY_V2",
        new BigDecimal("1.0000"), Set.of(), "test-fixture"));
  }

  private static SkillGapDiagnosis diagnosisOf(GapDiagnosisReport report, String skillCode) {
    return report.skills().stream()
        .filter(d -> d.skillCode().equals(skillCode))
        .findFirst().orElseThrow();
  }

  private void wire() {
    if (diagnosis == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      JdbcTemplate runtimeJdbc = new JdbcTemplate(dataSource);
      learners = new LearnerRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      CurriculumService curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      diagnosis = new GapDiagnosisService(
          curriculumService, masteryRepository, new LearnerService(learners));
    }
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery("SELECT current_database()")) {
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
