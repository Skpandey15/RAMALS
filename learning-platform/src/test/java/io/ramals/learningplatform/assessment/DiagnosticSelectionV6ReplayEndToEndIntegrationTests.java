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
 * M2-ADR-034 Amendment 4 golden scenarios A4-1, A4-5, A4-6, A4-9 and A4-10, end to end against real
 * PostgreSQL and the real, already-seeded KAFKA v2 curriculum/bank: {@code DiagnosticService} creates
 * the destination attempt exactly as a caller would, and {@link DiagnosticSelectionV6ReplayService}
 * replays it -- no fixture bypasses either.
 *
 * <p>This class publishes the real v2 assessment version and declares it {@code
 * DIAGNOSTIC_SELECTION_V6}, in only this class's disposable schema, the same accepted pattern
 * {@code HypothesisDrivenProbeSelectionPersistenceIntegrationTests} already established for V5.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticSelectionV6ReplayEndToEndIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID CURRICULUM_V2 = UUID.fromString("01900000-0000-7000-8000-000000000004");

  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ACKS_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000107");

  // Real item ids (V049/V052): ACKS_MCQ_A1 (d11) is the real ROOT_CAUSE_PROBE trigger for
  // ACKS_MCQ_A2 (d12, PRODUCER_IDEMPOTENCE's only item), authorized by V054's real e01 relationship
  // -- the exact same flagship fixture HypothesisDrivenProbeSelectionPersistenceIntegrationTests
  // uses for V5. Under DIAGNOSTIC_SELECTION_V6, this single miss genuinely authorizes THREE
  // relationship-type hypotheses (Amendment 3's own widening: every miss is tried under every
  // RELATIONSHIP_TYPE_PRIORITY type, never just the first match V5 itself stops at) with six total
  // surviving candidates, and -- with no real prior interaction evidence for any of them beyond this
  // one miss -- genuinely falls back at Step 1 (STEP1_INSUFFICIENT_EVIDENCE). V5's own
  // first-match-wins resolution (used for the actual fallback packet) still finds only
  // ROOT_CAUSE_PROBE's own candidate, ACKS_MCQ_A2 -- a real, reproducible V6 outcome, not a
  // contrived one.
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625");
  private static final UUID ACKS_MCQ_A2 = UUID.fromString("01900000-0000-7000-8000-000000000626");

  private static String databaseUrl;
  private DiagnosticService diagnostics;
  private DiagnosticSelectionV6ReplayService replayService;
  private DiagnosticSelectionReplayInputRepository replayInputs;
  private ProbeProvenanceRepository probeProvenanceRepository;
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

  // -------------------------------------------------------------------------------------------
  // A4-1 / A4-5: ordinary exact replay of a real V6 fallback outcome, reproducing the same
  // fallback reason and (via the already-exact core.diagnostic_probe_provenance record) the same
  // final selected probe.
  // -------------------------------------------------------------------------------------------

  @Test
  void endToEndFallbackDecisionIsExactlyReplayedAndVerified() {
    wire();
    Learner learner = learners.provisionForSubject("v6-e2e-replay");
    snapshot(learner.id(), BROKER_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learner.id(), ACKS_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    UUID sourceAttemptId = completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("v6-e2e-replay", "KAFKA", "key-1");
    UUID destinationAttemptId = creation.attempt().id();

    DiagnosticSelectionReplayInput header =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();
    assertThat(header.sourceAttemptId()).isEqualTo(sourceAttemptId);
    assertThat(header.activated()).isFalse();
    assertThat(header.fallbackReason()).isEqualTo(V6FallbackReason.STEP1_INSUFFICIENT_EVIDENCE);
    assertThat(header.actionableHypothesisCount()).isEqualTo(3);
    assertThat(header.candidateProbeCount()).isEqualTo(6);

    DiagnosticSelectionV6ReplayResult result = replayService.replay(destinationAttemptId);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().activated()).isFalse();
    assertThat(result.recomputedDecision().fallbackReason()).isEqualTo(V6FallbackReason.STEP1_INSUFFICIENT_EVIDENCE);
    assertThat(result.recomputedDecision().sourceAttemptId()).isEqualTo(sourceAttemptId);
    // A4-5: V6's own fallback defers to V5's identical resolution -- the packet's real, final
    // selected probe is already exactly replayable via the pre-existing, unmodified
    // core.diagnostic_probe_provenance record (Amendment 4 sec S), independent of this PR's own
    // new snapshot.
    assertThat(probeProvenanceRepository.findByAttemptAndItem(destinationAttemptId, ACKS_MCQ_A2))
        .as("V5's own fallback selection is recorded in the pre-existing provenance table")
        .isPresent();
  }

  // -------------------------------------------------------------------------------------------
  // A4-6: an idempotent retry produces exactly one attempt, one snapshot header, one candidate set.
  // -------------------------------------------------------------------------------------------

  @Test
  void idempotentRetryProducesExactlyOneSnapshotAndCandidateSet() {
    wire();
    Learner learner = learners.provisionForSubject("v6-e2e-idempotent");
    snapshot(learner.id(), BROKER_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learner.id(), ACKS_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation first = diagnostics.createAttempt("v6-e2e-idempotent", "KAFKA", "retry-key");
    AttemptCreation second = diagnostics.createAttempt("v6-e2e-idempotent", "KAFKA", "retry-key");

    assertThat(second.attempt().id()).isEqualTo(first.attempt().id());
    Integer headerCount = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.diagnostic_selection_replay_input WHERE destination_attempt_id = ?",
        Integer.class, first.attempt().id());
    assertThat(headerCount).isEqualTo(1);
    UUID replayInputId = replayInputs.findByDestinationAttempt(first.attempt().id()).orElseThrow().id();
    Integer candidateCount = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.diagnostic_selection_replay_candidate_probe WHERE replay_input_id = ?",
        Integer.class, replayInputId);
    // Six candidates (three relationship-type hypotheses over the one real miss -- see
    // ACKS_MCQ_A1's own fixture comment above), never duplicated by the retry.
    assertThat(candidateCount).isEqualTo(6);
  }

  // -------------------------------------------------------------------------------------------
  // A4-9 / A4-10: a later-completing (or later-eligible) source attempt must never change a replay
  // whose source was already fixed -- including the NO_SOURCE_ATTEMPT case.
  // -------------------------------------------------------------------------------------------

  @Test
  void noSourceAttemptReplayIsNeverOverwrittenByALaterEligibleSourceAttempt() {
    wire();
    Learner learner = learners.provisionForSubject("v6-e2e-no-source-then-later");
    // No completed attempt exists yet -- the destination decision below must find no source
    // attempt at all.
    AttemptCreation creation = diagnostics.createAttempt("v6-e2e-no-source-then-later", "KAFKA", "key-1");
    UUID destinationAttemptId = creation.attempt().id();

    DiagnosticSelectionReplayInput headerBeforeLaterAttempt =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();
    assertThat(headerBeforeLaterAttempt.sourceAttemptId()).isNull();
    assertThat(headerBeforeLaterAttempt.fallbackReason()).isEqualTo(V6FallbackReason.NO_SOURCE_ATTEMPT);

    // The destination attempt must finish before a new one can start under the same version
    // (uq_assessment_attempt_one_active) -- exactly the realistic shape this scenario takes: the
    // learner finishes attempt B, then later takes a new attempt that could (wrongly) look like an
    // eligible source if replay ever rediscovered it instead of trusting the persisted NULL.
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", destinationAttemptId);
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    DiagnosticSelectionV6ReplayResult result = replayService.replay(destinationAttemptId);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().sourceAttemptId())
        .as("the persisted NULL is authoritative -- replay never rediscovers the now-available source")
        .isNull();
    assertThat(result.recomputedDecision().fallbackReason()).isEqualTo(V6FallbackReason.NO_SOURCE_ATTEMPT);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID completedAttemptWithOneResponse(UUID learnerId, UUID itemVersionId, boolean isCorrect) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "v6-e2e-source-fixture-" + attemptId);
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
      probeProvenanceRepository = new ProbeProvenanceRepository(runtimeJdbc);
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
