package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * DIAGNOSTIC_SELECTION_V4 ("H4a": cross-attempt regression confirmation -- H4b's hypothesis-driven
 * related-probe selection is future work, no code under test here) against real PostgreSQL, through
 * {@link DiagnosticService#createAttempt} end to end -- proving the cross-attempt part actually
 * works: a learner's own persisted {@code ledger.mastery_snapshot} history, not a synthetic
 * in-memory set, is what {@code detectRegressedSkills} walks. A small throwaway, test-only
 * assessment version, same pattern as {@link PrerequisiteAwareSelectionPersistenceIntegrationTests},
 * so nothing here touches or publishes the real Kafka v1/v2 content.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class HypothesisConfirmationSelectionPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT = UUID.fromString("01900000-0000-7000-8000-000000000401");
  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID TOPIC_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000102");

  private static String databaseUrl;
  private static UUID testVersionId;
  private AssessmentRepository assessments;
  private LearnerRepository learners;
  private MasteryRepository masteryRepository;
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

    // A small, throwaway, test-only assessment version declaring V4 -- not the real Kafka v1/v2
    // content, so nothing here touches or publishes either. Real BROKER/TOPIC skill ids, though no
    // prerequisite edge between them is exercised here -- that composition is already covered by
    // PrerequisiteAwareSelectionPersistenceIntegrationTests; this class is about the regression
    // signal alone.
    testVersionId = UUID.randomUUID();
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      // DRAFT first: a published version's items are immutable
      // (core.protect_published_assessment_item), so the items and their lineage have to exist
      // before this row is published, not after.
      statement.execute("""
          INSERT INTO core.assessment_version
            (id, assessment_id, curriculum_version_id, version_code, status,
             selection_policy_version)
          VALUES ('%s', '%s', '%s', 'v4-test-fixture', 'DRAFT', 'DIAGNOSTIC_SELECTION_V4')
          """.formatted(testVersionId, ASSESSMENT, CURRICULUM));
      insertItem(statement, "01900000-0000-7000-8000-0000000008b1", testVersionId, BROKER_SKILL,
          "V4_TEST_BROKER_F", "FOUNDATIONAL", 1);
      insertItem(statement, "01900000-0000-7000-8000-0000000008b2", testVersionId, BROKER_SKILL,
          "V4_TEST_BROKER_I", "INTERMEDIATE", 2);
      insertItem(statement, "01900000-0000-7000-8000-0000000008b3", testVersionId, TOPIC_SKILL,
          "V4_TEST_TOPIC_F", "FOUNDATIONAL", 3);
      insertItem(statement, "01900000-0000-7000-8000-0000000008b4", testVersionId, TOPIC_SKILL,
          "V4_TEST_TOPIC_I", "INTERMEDIATE", 4);
      for (String itemId : List.of(
          "01900000-0000-7000-8000-0000000008b1",
          "01900000-0000-7000-8000-0000000008b2",
          "01900000-0000-7000-8000-0000000008b3",
          "01900000-0000-7000-8000-0000000008b4")) {
        statement.execute("""
            INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id)
            VALUES ('%s', gen_random_uuid())
            """.formatted(itemId));
      }
      statement.execute("""
          UPDATE core.assessment_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
           WHERE id = '%s'
          """.formatted(testVersionId));
    }
  }

  private static void insertItem(
      Statement statement, String id, UUID versionId, UUID skillId, String itemCode,
      String difficulty, int displayOrder) throws SQLException {
    statement.execute("""
        INSERT INTO core.assessment_item_version
          (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
           answer_key_jsonb, difficulty, display_order, trust_state, verified_by, verified_at)
        VALUES ('%s', '%s', '%s', '%s', 'SINGLE_CHOICE', 'Probe stem.',
                '[{"id":"A","text":"a"},{"id":"B","text":"b"}]'::jsonb, '{"correct":["A"]}'::jsonb,
                '%s', %d, 'VERIFIED_CONTENT', 'v4-test-fixture', CURRENT_TIMESTAMP)
        """.formatted(id, versionId, skillId, itemCode, difficulty, displayOrder));
  }

  @Test
  void aSkillWhoseLatestSnapshotRegressedIsPrioritisedForHypothesisConfirmation() {
    wire();
    Learner learner = learners.provisionForSubject("v4-regressed-skill");
    // BROKER: MASTERED (aggregate_version 1) then DEVELOPING (aggregate_version 2) -- a real
    // status regression, persisted as two ledger rows, not asserted from memory.
    snapshot(learner.id(), BROKER_SKILL, 1, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learner.id(), BROKER_SKILL, 2, MasteryStatus.DEVELOPING,
        Set.of(MasteryDifficultyBand.EASY));
    // TOPIC: a single snapshot with the same underlying evidence profile as BROKER's latest --
    // nothing to compare against, so it must never be treated as a regression regardless of how
    // "weak" it looks in isolation.
    snapshot(learner.id(), TOPIC_SKILL, 1, MasteryStatus.MASTERED, Set.of(MasteryDifficultyBand.EASY));

    AttemptCreation creation =
        diagnostics.createAttempt("v4-regressed-skill", "KAFKA", "key-1");

    assertThat(attemptPolicy(creation.attempt().id())[0])
        .isEqualTo(HypothesisConfirmationDiagnosticSelector.SELECTION_POLICY_VERSION);

    List<Object[]> brokerRows = itemRows(creation.attempt().id(), BROKER_SKILL);
    assertThat(brokerRows).isNotEmpty();
    // The reason changes for every selected item of this skill -- that is V4's whole adjustment.
    assertThat(brokerRows).allSatisfy(row ->
        assertThat(row[1]).isEqualTo(SelectionReason.HYPOTHESIS_CONFIRMATION.name()));
    // The band is whatever V2's own signal already decided for the latest snapshot (evidenced at
    // FOUNDATIONAL, so escalated to INTERMEDIATE) -- V4 must not touch it. The pool is small enough
    // that round-robin fill may also sweep up the FOUNDATIONAL item, so this asserts the escalated
    // band was reachable at all, not that every selected item sits at it (the same shape the V3
    // fixture's capped-band assertion has to account for, mirrored here in the other direction).
    assertThat(brokerRows).anySatisfy(row -> assertThat(row[0]).isEqualTo("INTERMEDIATE"));

    List<Object[]> topicRows = itemRows(creation.attempt().id(), TOPIC_SKILL);
    assertThat(topicRows).noneSatisfy(row ->
        assertThat(row[1]).isEqualTo(SelectionReason.HYPOTHESIS_CONFIRMATION.name()));
  }

  @Test
  void aSkillThatImprovedAcrossTwoSnapshotsIsNeverTreatedAsARegression() {
    wire();
    Learner learner = learners.provisionForSubject("v4-improved-skill");
    // BROKER: DEVELOPING (aggregate_version 1) then MASTERED (aggregate_version 2) -- genuine
    // progress, not a regression, even though two snapshots exist.
    snapshot(learner.id(), BROKER_SKILL, 1, MasteryStatus.DEVELOPING, Set.of(MasteryDifficultyBand.EASY));
    snapshot(learner.id(), BROKER_SKILL, 2, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));

    AttemptCreation creation =
        diagnostics.createAttempt("v4-improved-skill", "KAFKA", "key-1");

    List<Object[]> brokerRows = itemRows(creation.attempt().id(), BROKER_SKILL);
    assertThat(brokerRows).isNotEmpty();
    assertThat(brokerRows).noneSatisfy(row ->
        assertThat(row[1]).isEqualTo(SelectionReason.HYPOTHESIS_CONFIRMATION.name()));
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private void snapshot(
      UUID learnerId, UUID skillId, int aggregateVersion, MasteryStatus status,
      Set<MasteryDifficultyBand> coveredBands) {
    masteryRepository.ensureAggregate(learnerId, skillId, CURRICULUM);
    masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, CURRICULUM, aggregateVersion,
        new BigDecimal("1.0000"), status, new BigDecimal("0.7500"),
        new BigDecimal("1.0000"), new BigDecimal("0.7500"), 4, 8,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2", "MASTERY_STATUS_POLICY_V2",
        new BigDecimal("1.0000"), coveredBands, "test-fixture"));
  }

  private List<Object[]> itemRows(UUID attemptId, UUID skillId) {
    return runtimeJdbc.query("""
        SELECT iv.difficulty, ai.selection_reason
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_item_version iv ON iv.id = ai.item_version_id
        WHERE ai.attempt_id = ? AND iv.skill_id = ?
        """, (result, row) -> new Object[] {result.getString("difficulty"), result.getString("selection_reason")},
        attemptId, skillId);
  }

  private Object[] attemptPolicy(UUID attemptId) {
    return runtimeJdbc.query(
        "SELECT selection_policy, packet_policy FROM core.assessment_attempt WHERE id = ?",
        (result, row) -> new Object[] {result.getString("selection_policy"), result.getString("packet_policy")},
        attemptId).getFirst();
  }

  private void wire() {
    if (diagnostics == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      CurriculumService curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      diagnostics = new DiagnosticService(assessments, learnerService,
          new DiagnosticFormSelector(new DiagnosticFormProperties()),
          new AdaptiveDiagnosticSelector(new AdaptiveDiagnosticFormProperties()), masteryRepository,
          curriculumService);
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
