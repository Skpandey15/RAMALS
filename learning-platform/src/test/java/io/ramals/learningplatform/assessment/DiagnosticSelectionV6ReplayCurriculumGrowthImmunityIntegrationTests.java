package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContextAssembler;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyRepository;
import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.learner.Learner;
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
 * M2-ADR-034 Amendment 4 golden scenario A4-3, end to end against real PostgreSQL: a new probe
 * candidate published after a destination decision must never be considered by replay, even though
 * a live {@code ProbeRelationshipService.resolve} call today would find it.
 *
 * <p>Deliberately its own test class, not folded into {@code
 * DiagnosticSelectionV6ReplayEndToEndIntegrationTests}: this test mutates shared curriculum state
 * (tagging a real item to {@code PRODUCER_IDEMPOTENCE}) that would otherwise leak into every other
 * test method sharing that class's one {@code @BeforeAll} schema, silently changing their own
 * candidate counts depending on JUnit's (here, undeclared) method execution order. A fresh,
 * disposable schema per class -- this repository's own established pattern -- is what actually
 * isolates it.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticSelectionV6ReplayCurriculumGrowthImmunityIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID CURRICULUM_V2 = UUID.fromString("01900000-0000-7000-8000-000000000004");

  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ACKS_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000107");

  // Same real KAFKA v2 fixture as DiagnosticSelectionV6ReplayEndToEndIntegrationTests: ACKS_MCQ_A1's
  // one miss genuinely authorizes three relationship-type hypotheses / six candidates and falls back
  // at Step 1 (STEP1_INSUFFICIENT_EVIDENCE) for a fresh learner with no other evidence.
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625");
  private static final UUID PRODUCER_IDEMPOTENCE = UUID.fromString("01900000-0000-7000-8000-000000000d12");
  // A real, existing, VERIFIED_CONTENT item of the SAME skill as PRODUCER_IDEMPOTENCE (ACKS),
  // tagged elsewhere (d11) today -- reused as the "newly published candidate" by tagging it to
  // PRODUCER_IDEMPOTENCE too. core.assessment_item_version itself is immutable once its owning
  // version is PUBLISHED (trg_assessment_version_items_immutable), but core.assessment_item_objective
  // is not (only trg_assessment_item_objective_skill_match, which this item already satisfies) --
  // publishing a new tag between an EXISTING item and objective is the realistic shape curriculum
  // growth after a decision actually takes in this schema.
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624");

  private static String databaseUrl;
  private DiagnosticService diagnostics;
  private DiagnosticSelectionV6ReplayService replayService;
  private DiagnosticSelectionReplayInputRepository replayInputs;
  private LearnerRepository learners;
  private MasteryRepository masteryRepository;
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

    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("""
          UPDATE core.assessment_version
          SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
              selection_policy_version = 'DIAGNOSTIC_SELECTION_V6'
          WHERE id = '01900000-0000-7000-8000-000000000403'
          """);
    }
  }

  @Test
  void newlyPublishedCandidateAfterTheDecisionHasNoEffectOnReplay() {
    wire();
    Learner learner = learners.provisionForSubject("v6-a4-3-later-candidate");
    snapshot(learner.id(), BROKER_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learner.id(), ACKS_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("v6-a4-3-later-candidate", "KAFKA", "key-1");
    UUID destinationAttemptId = creation.attempt().id();
    DiagnosticSelectionReplayInput headerBeforePublication =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();
    assertThat(headerBeforePublication.candidateProbeCount()).isEqualTo(6);
    assertThat(headerBeforePublication.actionableHypothesisCount()).isEqualTo(3);

    // PRODUCER_IDEMPOTENCE (d12) has exactly one item before this. Tagging a second, real,
    // already-VERIFIED_CONTENT item to it now would, if replay ever re-ran
    // ProbeRelationshipService.resolve, hand the already-admitted ROOT_CAUSE_PROBE hypothesis a
    // second surviving candidate it never had at decision time.
    runtimeJdbc.update(
        "INSERT INTO core.assessment_item_objective (item_version_id, objective_id) VALUES (?, ?)",
        ACKS_MCQ_I2, PRODUCER_IDEMPOTENCE);

    DiagnosticSelectionV6ReplayResult result = replayService.replay(destinationAttemptId);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().candidateProbeCount())
        .as("the newly published candidate must never be counted")
        .isEqualTo(headerBeforePublication.candidateProbeCount())
        .isEqualTo(6);
    assertThat(result.recomputedDecision().actionableHypothesisCount())
        .isEqualTo(headerBeforePublication.actionableHypothesisCount())
        .isEqualTo(3);
  }

  private UUID completedAttemptWithOneResponse(UUID learnerId, UUID itemVersionId, boolean isCorrect) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "v6-a4-3-source-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"selected":["A"]}'::jsonb, ?)
        """, UUID.randomUUID(), attemptId, itemVersionId, isCorrect);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
    return attemptId;
  }

  private void snapshot(UUID learnerId, UUID skillId, MasteryStatus status, Set<MasteryDifficultyBand> bands) {
    masteryRepository.ensureAggregate(learnerId, skillId, CURRICULUM_V2);
    masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, CURRICULUM_V2, 1,
        new BigDecimal("1.0000"), status, new BigDecimal("0.7500"),
        new BigDecimal("1.0000"), new BigDecimal("0.7500"), 4, 8,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2", "MASTERY_STATUS_POLICY_V2",
        new BigDecimal("1.0000"), bands, "test-fixture"));
  }

  private void wire() {
    if (diagnostics == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      AssessmentRepository assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      CurriculumService curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      ProbeRelationshipService probeRelationshipService =
          new ProbeRelationshipService(new ProbeRelationshipRepository(runtimeJdbc), assessments);
      ProbeProvenanceRepository probeProvenanceRepository = new ProbeProvenanceRepository(runtimeJdbc);
      replayInputs = new DiagnosticSelectionReplayInputRepository(runtimeJdbc);
      HypothesisDiscriminationDiagnosticSelector selector = new HypothesisDiscriminationDiagnosticSelector(
          assessments, probeRelationshipService,
          new HypothesisUncertaintyContextAssembler(new HypothesisUncertaintyRepository(runtimeJdbc)),
          new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1()),
          new HypothesisDiscriminationCalculatorV1(
              new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1())));
      diagnostics = new DiagnosticService(assessments, learnerService,
          new DiagnosticFormSelector(new DiagnosticFormProperties()),
          new AdaptiveDiagnosticSelector(new AdaptiveDiagnosticFormProperties()), masteryRepository,
          curriculumService, probeRelationshipService, probeProvenanceRepository, selector, replayInputs);
      replayService = new DiagnosticSelectionV6ReplayService(
          assessments, replayInputs, probeProvenanceRepository, selector);
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
