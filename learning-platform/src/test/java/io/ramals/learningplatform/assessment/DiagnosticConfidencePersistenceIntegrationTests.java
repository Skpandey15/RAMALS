package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.EvidenceConfidenceCalculatorV2;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshotDraft;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.mastery.MasteryStatusPolicyV2;
import io.ramals.learningplatform.mastery.WeightedMasteryCalculator;
import io.ramals.learningplatform.observability.UuidV7;
import io.ramals.learningplatform.recommendation.RecommendationPolicy;
import io.ramals.learningplatform.recommendation.RecommendationRepository;
import io.ramals.learningplatform.recommendation.RecommendationService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-026) against real PostgreSQL and the real, already-seeded KAFKA
 * v2 curriculum/bank and #251's real seeded relationships (V054) -- the same accepted pattern
 * {@code HypothesisDrivenProbeSelectionPersistenceIntegrationTests} already established for V5,
 * extended one step further: a probe response is actually submitted through
 * {@code DiagnosticSubmissionService.submit}, so H5's own wiring inside that transaction is
 * exercised end to end, not just the calculator in isolation.
 *
 * <p>Prior evidence for the accumulation test is seeded directly (a fixture "trigger" attempt, a
 * fixture "probe" attempt, and a provenance row referencing them) -- the same fixture-over-real-flow
 * pattern already used throughout this suite for a "prior attempt" whose exact shape must be
 * controlled precisely, since the real KAFKA v2 content only ever authors one item for the target
 * objective in this scenario (no-repeat exposure means a second real, selector-driven probe of the
 * identical target is never reachable).
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticConfidencePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID CURRICULUM_V2 = UUID.fromString("01900000-0000-7000-8000-000000000004");

  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ACKS_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000107");

  // Both tagged to ACKS_DURABILITY_TRADEOFFS (d11); either miss alone resolves the identical
  // ROOT_CAUSE_PROBE -> ACKS_MCQ_A2 (d12) hypothesis -- see HypothesisDrivenProbeSelectionPersistenceIntegrationTests.
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624");
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625");
  // d12, PRODUCER_IDEMPOTENCE's only item -- authored ADVANCED, correct answer "B" (V049).
  private static final UUID ACKS_MCQ_A2 = UUID.fromString("01900000-0000-7000-8000-000000000626");

  private static final UUID SOURCE_OBJECTIVE_D11 = UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID TARGET_OBJECTIVE_D12 = UUID.fromString("01900000-0000-7000-8000-000000000d12");
  private static final UUID AUTHORIZING_RELATIONSHIP_E01 =
      UUID.fromString("01900000-0000-7000-8000-000000000e01");

  private static String databaseUrl;
  private LearnerRepository learners;
  private MasteryRepository masteryRepository;
  private ProbeProvenanceRepository probeProvenanceRepository;
  private DiagnosticConfidenceRepository confidenceRepository;
  private DiagnosticService diagnostics;
  private DiagnosticSubmissionService submissions;
  private TransactionTemplate transactionTemplate;
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
              selection_policy_version = 'DIAGNOSTIC_SELECTION_V5'
          WHERE id = '01900000-0000-7000-8000-000000000403'
          """);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Flagship end-to-end flow: real V5 selection, real submission, one appended observation.
  // -------------------------------------------------------------------------------------------

  @Test
  void aSubmittedProbeResponseAppendsOneConfidenceObservationWithFullProvenance() {
    wire();
    Learner learner = learners.provisionForSubject("h5-flagship");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("h5-flagship", "KAFKA", "key-2");
    Optional<ProbeProvenance> provenance =
        probeProvenanceRepository.findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2);
    assertThat(provenance).isPresent();

    // Answered incorrectly ("A", not the seeded correct "B") -- SUPPORTING evidence.
    submit("h5-flagship", creation.attempt().id(), new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(ACKS_MCQ_A2.toString(), List.of("A")))));

    Optional<DiagnosticConfidenceObservation> observation =
        confidenceRepository.findByTriggeringProvenanceId(provenance.get().id());
    assertThat(observation).isPresent();
    DiagnosticConfidenceObservation obs = observation.get();
    assertThat(obs.learnerId()).isEqualTo(learner.id());
    assertThat(obs.sourceObjectiveId()).isEqualTo(SOURCE_OBJECTIVE_D11);
    assertThat(obs.targetObjectiveId()).isEqualTo(TARGET_OBJECTIVE_D12);
    assertThat(obs.relationshipType()).isEqualTo(ProbeRelationshipType.ROOT_CAUSE_PROBE);
    assertThat(obs.supportingCount()).isEqualTo(1);
    assertThat(obs.contradictoryCount()).isEqualTo(0);
    assertThat(obs.inconclusiveCount()).isEqualTo(0);
    assertThat(obs.band()).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(obs.policyVersion()).isEqualTo(DiagnosticConfidenceCalculatorV1.POLICY_VERSION);
  }

  @Test
  void aCorrectlyAnsweredProbeResponseIsContradictoryEvidence() {
    wire();
    Learner learner = learners.provisionForSubject("h5-contradictory");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("h5-contradictory", "KAFKA", "key-2");
    UUID provenanceId = probeProvenanceRepository
        .findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2).orElseThrow().id();

    // Answered correctly ("B", the seeded correct answer) -- CONTRADICTORY evidence.
    submit("h5-contradictory", creation.attempt().id(), new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(ACKS_MCQ_A2.toString(), List.of("B")))));

    DiagnosticConfidenceObservation obs =
        confidenceRepository.findByTriggeringProvenanceId(provenanceId).orElseThrow();
    assertThat(obs.supportingCount()).isEqualTo(0);
    assertThat(obs.contradictoryCount()).isEqualTo(1);
    assertThat(obs.band()).isEqualTo(DiagnosticConfidenceBand.LOW);
  }

  // -------------------------------------------------------------------------------------------
  // Accumulation: prior evidence (seeded directly, since the real content/no-repeat exposure
  // cannot itself produce a second real probe of the same one-item target objective -- once ANY
  // attempt has presented ACKS_MCQ_A2, the real V5 selector correctly refuses to serve it again)
  // combines with a newly-submitted real probe response to reach the correct band. The triggering
  // attempt itself is also seeded directly, exactly matching the shape V5's own selector would
  // have produced (an IN_PROGRESS attempt presenting the probe item, with its provenance row
  // already written) -- what is exercised for real is DiagnosticSubmissionService.submit's own
  // transactional read-and-append wiring, the thing this test actually verifies, not V5 selection
  // itself (already covered by HypothesisDrivenProbeSelectionPersistenceIntegrationTests).
  // -------------------------------------------------------------------------------------------

  @Test
  void accumulatedEvidenceAcrossMultipleObservationsReachesHigh() {
    wire();
    Learner learner = learners.provisionForSubject("h5-accumulate");
    secureBrokerAndAcks(learner.id());

    // Two prior SUPPORTING observations for the identical hypothesis tuple, seeded directly.
    seedPriorProbeEvidence(learner.id(), ACKS_MCQ_I2, false);
    seedPriorProbeEvidence(learner.id(), ACKS_MCQ_A1, false);

    UUID triggeringAttemptId = completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);
    UUID probeAttemptId = inProgressAttemptPresentingProbe(
        learner.id(), triggeringAttemptId, ACKS_MCQ_A1);
    UUID provenanceId = probeProvenanceRepository
        .findByAttemptAndItem(probeAttemptId, ACKS_MCQ_A2).orElseThrow().id();

    // A third SUPPORTING observation -- 3 total, 0 contradictory -> HIGH.
    submit("h5-accumulate", probeAttemptId, new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(ACKS_MCQ_A2.toString(), List.of("A")))));

    DiagnosticConfidenceObservation obs =
        confidenceRepository.findByTriggeringProvenanceId(provenanceId).orElseThrow();
    assertThat(obs.supportingCount()).isEqualTo(3);
    assertThat(obs.contradictoryCount()).isEqualTo(0);
    assertThat(obs.band()).isEqualTo(DiagnosticConfidenceBand.HIGH);
  }

  /** Seeds an IN_PROGRESS attempt presenting ACKS_MCQ_A2 with its provenance row already written --
   * exactly the shape {@code DiagnosticService.selectHypothesisDrivenProbeForm} leaves an attempt
   * in before it is ever submitted, so that {@code DiagnosticSubmissionService.submit}'s own
   * transactional wiring can be exercised for real without depending on the real selector being
   * able to re-select an already-exposed item. */
  private UUID inProgressAttemptPresentingProbe(
      UUID learnerId, UUID sourceAttemptId, UUID sourceItemVersionId) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "h5-trigger-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'HYPOTHESIS_DRIVEN_PROBE')
        """, UUID.randomUUID(), attemptId, ACKS_MCQ_A2);
    runtimeJdbc.update("""
        INSERT INTO core.diagnostic_probe_provenance
          (id, attempt_id, item_version_id, source_attempt_id, source_item_version_id,
           source_objective_id, relationship_type, target_objective_id, authorizing_relationship_id)
        VALUES (?, ?, ?, ?, ?, ?, 'ROOT_CAUSE_PROBE', ?, ?)
        """, UuidV7.generate(), attemptId, ACKS_MCQ_A2, sourceAttemptId, sourceItemVersionId,
        SOURCE_OBJECTIVE_D11, TARGET_OBJECTIVE_D12, AUTHORIZING_RELATIONSHIP_E01);
    return attemptId;
  }

  // -------------------------------------------------------------------------------------------
  // An ordinary, non-probe response never writes an observation -- even when submitted in the
  // same request as a real probe response.
  // -------------------------------------------------------------------------------------------

  @Test
  void anOrdinaryNonProbeResponseInTheSameSubmissionWritesNoObservationOfItsOwn() {
    wire();
    Learner learner = learners.provisionForSubject("h5-non-probe");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("h5-non-probe", "KAFKA", "key-2");
    List<UUID> presented = presentedItemIds(creation.attempt().id());
    UUID ordinaryItem = presented.stream().filter(id -> !id.equals(ACKS_MCQ_A2)).findFirst()
        .orElseThrow(() -> new IllegalStateException("packet had no non-probe item to test with"));

    Long observationCountBefore = observationCount();
    submit("h5-non-probe", creation.attempt().id(), new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ordinaryItem.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_A2.toString(), List.of("A")))));

    // Exactly one new observation -- the probe's -- never one for the ordinary item too.
    assertThat(observationCount() - observationCountBefore).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Idempotency: DiagnosticSubmissionService's existing "already COMPLETED -> no writes"
  // guarantee extends to H5 -- a duplicate submit never appends a second observation.
  // -------------------------------------------------------------------------------------------

  @Test
  void duplicateSubmitWritesNoSecondObservation() {
    wire();
    Learner learner = learners.provisionForSubject("h5-idempotent");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("h5-idempotent", "KAFKA", "key-2");
    DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(ACKS_MCQ_A2.toString(), List.of("A"))));

    submit("h5-idempotent", creation.attempt().id(), request);
    Long observationCountAfterFirst = observationCount();
    submit("h5-idempotent", creation.attempt().id(), request);
    Long observationCountAfterSecond = observationCount();

    assertThat(observationCountAfterSecond).isEqualTo(observationCountAfterFirst);
  }

  // -------------------------------------------------------------------------------------------
  // DB invariants: direct, invalid writes against the hardened schema are rejected.
  // -------------------------------------------------------------------------------------------

  @Test
  void observationRowsAreImmutable() {
    wire();
    Learner learner = learners.provisionForSubject("h5-immutable");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);
    AttemptCreation creation = diagnostics.createAttempt("h5-immutable", "KAFKA", "key-2");
    submit("h5-immutable", creation.attempt().id(), new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(ACKS_MCQ_A2.toString(), List.of("A")))));
    UUID observationId = runtimeJdbc.queryForObject(
        "SELECT id FROM core.diagnostic_confidence_observation ORDER BY created_at DESC, id DESC LIMIT 1",
        UUID.class);

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.diagnostic_confidence_observation SET band = 'HIGH' WHERE id = ?", observationId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.diagnostic_confidence_observation WHERE id = ?", observationId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void aDuplicateTriggeringProvenanceIdIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("h5-dup-provenance");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);
    AttemptCreation creation = diagnostics.createAttempt("h5-dup-provenance", "KAFKA", "key-2");
    submit("h5-dup-provenance", creation.attempt().id(), new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(ACKS_MCQ_A2.toString(), List.of("A")))));
    UUID triggeringProvenanceId = probeProvenanceRepository
        .findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2).orElseThrow().id();

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.diagnostic_confidence_observation
          (id, learner_id, source_objective_id, target_objective_id, relationship_type,
           triggering_provenance_id, supporting_count, contradictory_count, inconclusive_count,
           band, policy_version)
        VALUES (?, ?, ?, ?, 'ROOT_CAUSE_PROBE', ?, 1, 0, 0, 'LOW', 'DIAGNOSTIC_CONFIDENCE_V1')
        """, UuidV7.generate(), learner.id(), SOURCE_OBJECTIVE_D11, TARGET_OBJECTIVE_D12,
        triggeringProvenanceId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void aNonexistentTriggeringProvenanceIdIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("h5-fk-provenance");
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.diagnostic_confidence_observation
          (id, learner_id, source_objective_id, target_objective_id, relationship_type,
           triggering_provenance_id, supporting_count, contradictory_count, inconclusive_count,
           band, policy_version)
        VALUES (?, ?, ?, ?, 'ROOT_CAUSE_PROBE', ?, 1, 0, 0, 'LOW', 'DIAGNOSTIC_CONFIDENCE_V1')
        """, UuidV7.generate(), learner.id(), SOURCE_OBJECTIVE_D11, TARGET_OBJECTIVE_D12,
        UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void anInvalidBandValueIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("h5-invalid-band");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);
    AttemptCreation creation = diagnostics.createAttempt("h5-invalid-band", "KAFKA", "key-2");
    UUID triggeringProvenanceId = probeProvenanceRepository
        .findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2).orElseThrow().id();

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.diagnostic_confidence_observation
          (id, learner_id, source_objective_id, target_objective_id, relationship_type,
           triggering_provenance_id, supporting_count, contradictory_count, inconclusive_count,
           band, policy_version)
        VALUES (?, ?, ?, ?, 'ROOT_CAUSE_PROBE', ?, 1, 0, 0, 'CERTAIN', 'DIAGNOSTIC_CONFIDENCE_V1')
        """, UuidV7.generate(), learner.id(), SOURCE_OBJECTIVE_D11, TARGET_OBJECTIVE_D12,
        triggeringProvenanceId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void aMismatchedPolicyVersionIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("h5-invalid-policy");
    secureBrokerAndAcks(learner.id());
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);
    AttemptCreation creation = diagnostics.createAttempt("h5-invalid-policy", "KAFKA", "key-2");
    UUID triggeringProvenanceId = probeProvenanceRepository
        .findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2).orElseThrow().id();

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.diagnostic_confidence_observation
          (id, learner_id, source_objective_id, target_objective_id, relationship_type,
           triggering_provenance_id, supporting_count, contradictory_count, inconclusive_count,
           band, policy_version)
        VALUES (?, ?, ?, ?, 'ROOT_CAUSE_PROBE', ?, 1, 0, 0, 'LOW', 'DIAGNOSTIC_CONFIDENCE_V2')
        """, UuidV7.generate(), learner.id(), SOURCE_OBJECTIVE_D11, TARGET_OBJECTIVE_D12,
        triggeringProvenanceId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private void secureBrokerAndAcks(UUID learnerId) {
    snapshot(learnerId, BROKER_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learnerId, ACKS_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
  }

  private void snapshot(UUID learnerId, UUID skillId, MasteryStatus status, Set<MasteryDifficultyBand> bands) {
    masteryRepository.ensureAggregate(learnerId, skillId, CURRICULUM_V2);
    masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, CURRICULUM_V2, 1,
        new BigDecimal("1.0000"), status, new BigDecimal("0.7500"),
        new BigDecimal("1.0000"), new BigDecimal("0.7500"), 4, 8,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2", "MASTERY_STATUS_POLICY_V2",
        new BigDecimal("1.0000"), bands, "test-fixture"));
    // Keeps core.learner_skill_aggregate's own version counter in step with the fixture snapshot
    // just inserted at version 1 -- otherwise MasteryService.recompute (which this class's real
    // submissions trigger, unlike the read-only HypothesisDrivenProbeSelectionPersistenceIntegrationTests
    // this fixture pattern is drawn from) computes the same next version and collides with it.
    masteryRepository.advanceAggregateVersion(learnerId, skillId, CURRICULUM_V2, 1);
  }

  private UUID completedAttemptWithOneResponse(UUID learnerId, UUID itemVersionId, boolean isCorrect) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "h5-source-fixture-" + attemptId);
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

  /** Seeds one prior, fully-formed distinct evidence observation for the same (d11 -> d12,
   * ROOT_CAUSE_PROBE) hypothesis tuple: a fixture trigger attempt (presenting {@code triggerItem},
   * tagged d11), a fixture probe attempt (presenting ACKS_MCQ_A2), and the provenance row linking
   * them -- exactly what a real V5 selection + submission would have produced, without depending
   * on no-repeat exposure (which the real selector enforces, but a raw fixture is not subject to)
   * to allow the same one-item target objective to be "probed" more than once.
   *
   * <p>Ordering matters: {@code trg_probe_provenance_guard} only admits a provenance insert while
   * its owning attempt is still {@code IN_PROGRESS}, the same constraint
   * {@code DiagnosticService.selectHypothesisDrivenProbeForm} satisfies by writing provenance
   * during attempt creation, before the attempt is ever completed -- so the probe attempt here is
   * completed only after its provenance row is written, never before. */
  private void seedPriorProbeEvidence(UUID learnerId, UUID triggerItem, boolean probeAnswerCorrect) {
    UUID triggerAttemptId = completedAttemptWithOneResponse(learnerId, triggerItem, false);

    UUID probeAttemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, probeAttemptId, learnerId, ASSESSMENT_V2, "h5-probe-fixture-" + probeAttemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'HYPOTHESIS_DRIVEN_PROBE')
        """, UUID.randomUUID(), probeAttemptId, ACKS_MCQ_A2);
    runtimeJdbc.update("""
        INSERT INTO core.diagnostic_probe_provenance
          (id, attempt_id, item_version_id, source_attempt_id, source_item_version_id,
           source_objective_id, relationship_type, target_objective_id, authorizing_relationship_id)
        VALUES (?, ?, ?, ?, ?, ?, 'ROOT_CAUSE_PROBE', ?, ?)
        """, UuidV7.generate(), probeAttemptId, ACKS_MCQ_A2, triggerAttemptId, triggerItem,
        SOURCE_OBJECTIVE_D11, TARGET_OBJECTIVE_D12, AUTHORIZING_RELATIONSHIP_E01);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"selected":["A"]}'::jsonb, ?)
        """, UUID.randomUUID(), probeAttemptId, ACKS_MCQ_A2, probeAnswerCorrect);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", probeAttemptId);
  }

  private List<UUID> presentedItemIds(UUID attemptId) {
    return runtimeJdbc.query(
        "SELECT item_version_id FROM core.assessment_attempt_item WHERE attempt_id = ?",
        (result, row) -> result.getObject("item_version_id", UUID.class), attemptId);
  }

  private Long observationCount() {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.diagnostic_confidence_observation", Long.class);
  }

  private SubmissionResult submit(String subject, UUID attemptId, DiagnosticSubmissionRequest request) {
    MDC.put("interactionId", "01920000-0000-7000-8000-0000000000e2");
    try {
      return transactionTemplate.execute(
          status -> submissions.submit(subject, "KAFKA", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }
  }

  private void wire() {
    if (diagnostics == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      AssessmentRepository assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      CurriculumService curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      ProbeRelationshipService probeRelationshipService =
          new ProbeRelationshipService(new ProbeRelationshipRepository(runtimeJdbc), assessments);
      probeProvenanceRepository = new ProbeProvenanceRepository(runtimeJdbc);
      confidenceRepository = new DiagnosticConfidenceRepository(runtimeJdbc);
      DiagnosticConfidenceService diagnosticConfidenceService = new DiagnosticConfidenceService(
          probeProvenanceRepository, confidenceRepository, new DiagnosticConfidenceCalculatorV1());

      diagnostics = new DiagnosticService(assessments, learnerService,
          new DiagnosticFormSelector(new DiagnosticFormProperties()),
          new AdaptiveDiagnosticSelector(new AdaptiveDiagnosticFormProperties()), masteryRepository,
          curriculumService, probeRelationshipService, probeProvenanceRepository);

      EvidenceRepository evidenceRepository = new EvidenceRepository(runtimeJdbc);
      EvidenceService evidenceService = new EvidenceService(evidenceRepository);
      MasteryService masteryService = new MasteryService(
          masteryRepository, evidenceRepository, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculatorV2(), new MasteryStatusPolicyV2());
      RecommendationService recommendationService = new RecommendationService(
          new RecommendationPolicy(), new RecommendationRepository(runtimeJdbc), learnerService);

      submissions = new DiagnosticSubmissionService(assessments, learnerService,
          new DiagnosticScorerV2(), evidenceService, masteryService, recommendationService,
          diagnosticConfidenceService, mapper);
      transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
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
