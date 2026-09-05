package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.EvidenceConfidenceCalculatorV2;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.mastery.MasteryStatusPolicyV2;
import io.ramals.learningplatform.mastery.WeightedMasteryCalculator;
import io.ramals.learningplatform.recommendation.RecommendationPolicy;
import io.ramals.learningplatform.recommendation.RecommendationRepository;
import io.ramals.learningplatform.recommendation.RecommendationService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Granular diagnostic confidence (M2-ADR-028) against real PostgreSQL and the real, already-seeded
 * KAFKA v2 assessment bank -- every scenario goes through the real {@code
 * DiagnosticSubmissionService.submit} flow end to end, since M2-ADR-028's central guarantee (exactly
 * one snapshot per affected misconception per submission, computed after the whole per-response loop)
 * is a property of that orchestration, not of {@link MisconceptionConfidenceService} in isolation.
 *
 * <p>Uses two real, already-seeded SINGLE_CHOICE items with an identical shape (options A/B/C/D,
 * correct B): {@code ACKS_MCQ_A1} (KAFKA_V2_ACKS_MCQ_A1) and {@code ACKS_MCQ_I2}
 * (KAFKA_V2_ACKS_MCQ_I2) -- having two distinct real items lets a single attempt answer both and
 * thereby produce two independent evidence observations for the same misconception within one
 * submission, which several scenarios below specifically need. Fresh, test-scoped misconceptions and
 * mappings are authored against them per test, the same "real content, no invented ids" discipline
 * {@code GranularDiagnosticRuntimeEvidencePersistenceIntegrationTests} already established for #255.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class GranularDiagnosticConfidencePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID ACKS_DURABILITY_TRADEOFFS = UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624"); // correct: B
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625"); // correct: B

  private static String databaseUrl;
  private JdbcTemplate runtimeJdbc;
  private LearnerRepository learners;
  private MisconceptionRepository misconceptions;
  private MisconceptionOptionMappingRepository mappings;
  private MisconceptionEvidenceObservationRepository evidenceObservations;
  private MisconceptionConfidenceRepository confidenceRepository;
  private DiagnosticSubmissionService submissions;
  private TransactionTemplate transactionTemplate;

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

  // -------------------------------------------------------------------------------------------
  // Truth table, through the real submission flow.
  // -------------------------------------------------------------------------------------------

  @Test
  void inconclusiveOnlyEvidenceYieldsInsufficientEvidenceAndIsNotDroppedFromTheCount() {
    wire();
    // Mapped only to "C" -- a wrong-but-untagged "A" answer is INCONCLUSIVE, not SUPPORTING.
    UUID misconceptionId = newPublishedMisconception("inconclusive-only fixture");
    mapAndPublish(ACKS_MCQ_A1, "C", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-inconclusive-only");
    UUID attemptId = freshAttempt(learner.id());

    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    MisconceptionConfidenceObservation snapshot =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionId).orElseThrow();
    assertThat(snapshot.supportingCount()).isZero();
    assertThat(snapshot.contradictoryCount()).isZero();
    assertThat(snapshot.inconclusiveCount()).isEqualTo(1);
    assertThat(snapshot.band()).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
    assertThat(snapshot.policyVersion()).isEqualTo(DiagnosticConfidenceCalculatorV1.POLICY_VERSION);
  }

  @Test
  void oneSupportingObservationIsLow() {
    wire();
    UUID misconceptionId = newPublishedMisconception("one supporting fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-one-supporting");
    UUID attemptId = freshAttempt(learner.id());

    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    MisconceptionConfidenceObservation snapshot =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionId).orElseThrow();
    assertThat(snapshot.supportingCount()).isEqualTo(1);
    assertThat(snapshot.band()).isEqualTo(DiagnosticConfidenceBand.LOW);
  }

  @Test
  void threeSupportingObservationsAcrossThreeSeparateSubmissionsIsHigh() {
    wire();
    // Also proves cross-submission (and therefore cross-assessment-version-capable) aggregation
    // (M2-ADR-028 SS2): each attempt is its own separate submission, yet all three contribute to one
    // final band.
    UUID misconceptionId = newPublishedMisconception("three supporting fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-three-supporting");

    UUID attempt1 = freshAttempt(learner.id());
    submit(learner.subject(), attempt1, oneResponse(ACKS_MCQ_A1, "A"));
    UUID attempt2 = freshAttempt(learner.id());
    submit(learner.subject(), attempt2, oneResponse(ACKS_MCQ_A1, "A"));
    UUID attempt3 = freshAttempt(learner.id());
    submit(learner.subject(), attempt3, oneResponse(ACKS_MCQ_A1, "A"));

    MisconceptionConfidenceObservation snapshot =
        confidenceRepository.findByAttemptAndMisconception(attempt3, misconceptionId).orElseThrow();
    assertThat(snapshot.supportingCount()).isEqualTo(3);
    assertThat(snapshot.band()).isEqualTo(DiagnosticConfidenceBand.HIGH);
  }

  @Test
  void contradictoryOnlyEvidenceIsLow() {
    wire();
    UUID misconceptionId = newPublishedMisconception("contradictory-only fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-contradictory-only");
    UUID attemptId = freshAttempt(learner.id());

    // "B" is the real correct answer -- CONTRADICTORY for every eligible misconception.
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "B"));

    MisconceptionConfidenceObservation snapshot =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionId).orElseThrow();
    assertThat(snapshot.supportingCount()).isZero();
    assertThat(snapshot.contradictoryCount()).isEqualTo(1);
    assertThat(snapshot.band()).isEqualTo(DiagnosticConfidenceBand.LOW);
  }

  @Test
  void mixedEvidenceWhereSupportingDominatesReachesHigh() {
    wire();
    // 4 SUPPORTING, 1 CONTRADICTORY: dominance test 4 > 3*1 -- HIGH despite one contradiction.
    UUID misconceptionId = newPublishedMisconception("mixed dominant fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-mixed-dominant");

    for (int i = 0; i < 4; i++) {
      UUID attemptId = freshAttempt(learner.id());
      submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));
    }
    UUID lastAttemptId = freshAttempt(learner.id());
    submit(learner.subject(), lastAttemptId, oneResponse(ACKS_MCQ_A1, "B"));

    MisconceptionConfidenceObservation snapshot =
        confidenceRepository.findByAttemptAndMisconception(lastAttemptId, misconceptionId).orElseThrow();
    assertThat(snapshot.supportingCount()).isEqualTo(4);
    assertThat(snapshot.contradictoryCount()).isEqualTo(1);
    assertThat(snapshot.band()).isEqualTo(DiagnosticConfidenceBand.HIGH);
  }

  // -------------------------------------------------------------------------------------------
  // Isolation and one-snapshot-per-submission batching.
  // -------------------------------------------------------------------------------------------

  @Test
  void twoLearnersWithTheSameMisconceptionMappingAreFullyIsolated() {
    wire();
    UUID misconceptionId = newPublishedMisconception("two learners fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learnerOne = learners.provisionForSubject("confidence-isolation-learner-one");
    Learner learnerTwo = learners.provisionForSubject("confidence-isolation-learner-two");

    UUID attemptOne = freshAttempt(learnerOne.id());
    submit(learnerOne.subject(), attemptOne, oneResponse(ACKS_MCQ_A1, "A"));
    UUID attemptTwo = freshAttempt(learnerTwo.id());
    submit(learnerTwo.subject(), attemptTwo, oneResponse(ACKS_MCQ_A1, "B"));

    MisconceptionConfidenceObservation snapshotOne =
        confidenceRepository.findByAttemptAndMisconception(attemptOne, misconceptionId).orElseThrow();
    MisconceptionConfidenceObservation snapshotTwo =
        confidenceRepository.findByAttemptAndMisconception(attemptTwo, misconceptionId).orElseThrow();
    assertThat(snapshotOne.supportingCount()).isEqualTo(1);
    assertThat(snapshotOne.contradictoryCount()).isZero();
    assertThat(snapshotTwo.supportingCount()).isZero();
    assertThat(snapshotTwo.contradictoryCount()).isEqualTo(1);
  }

  @Test
  void oneSubmissionAffectingTwoDistinctMisconceptionsPersistsTwoIsolatedSnapshots() {
    wire();
    UUID misconceptionX = newPublishedMisconception("multi-misconception fixture x");
    UUID misconceptionY = newPublishedMisconception("multi-misconception fixture y");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionX);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionY);
    Learner learner = learners.provisionForSubject("confidence-two-misconceptions");
    UUID attemptId = freshAttempt(learner.id());

    submit(learner.subject(), attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));

    MisconceptionConfidenceObservation snapshotX =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionX).orElseThrow();
    MisconceptionConfidenceObservation snapshotY =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionY).orElseThrow();
    assertThat(snapshotX.supportingCount()).isEqualTo(1);
    assertThat(snapshotY.supportingCount()).isEqualTo(1);
    assertThat(snapshotX.id()).isNotEqualTo(snapshotY.id());
  }

  @Test
  void oneSubmissionWithTwoResponsesForTheSameMisconceptionYieldsExactlyOneSnapshot() {
    wire();
    // The Stage 1.2 correction this test exists to prove: two responses in one submission that both
    // produce evidence for the SAME misconception must never manufacture two snapshots (Q1->M1
    // snapshot, Q2->M1 snapshot) -- exactly one, computed once after the whole loop, with both
    // pieces of evidence already folded in.
    UUID misconceptionId = newPublishedMisconception("single-snapshot-per-submission fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-one-snapshot-per-submission");
    UUID attemptId = freshAttempt(learner.id());

    submit(learner.subject(), attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));

    assertThat(confidenceObservationCountFor(attemptId, misconceptionId)).isEqualTo(1);
    MisconceptionConfidenceObservation snapshot =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionId).orElseThrow();
    assertThat(snapshot.supportingCount()).isEqualTo(2);
    assertThat(snapshot.band()).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(confidenceRepository.findProvenanceFor(snapshot.id())).hasSize(2);
  }

  // -------------------------------------------------------------------------------------------
  // Idempotency, immutability, provenance reconstruction, transactional rollback.
  // -------------------------------------------------------------------------------------------

  @Test
  void aDuplicateSubmissionProducesNoAdditionalConfidenceSnapshot() {
    wire();
    UUID misconceptionId = newPublishedMisconception("duplicate submission fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-duplicate-submission");
    UUID attemptId = freshAttempt(learner.id());
    DiagnosticSubmissionRequest request = oneResponse(ACKS_MCQ_A1, "A");

    submit(learner.subject(), attemptId, request);
    submit(learner.subject(), attemptId, request);

    assertThat(confidenceObservationCountFor(attemptId, misconceptionId)).isEqualTo(1);
  }

  @Test
  void aPersistedConfidenceSnapshotIsImmutable() {
    wire();
    UUID misconceptionId = newPublishedMisconception("immutable snapshot fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-immutable-snapshot");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.misconception_confidence_observation SET band = 'HIGH' "
            + "WHERE attempt_id = ? AND misconception_id = ?",
        attemptId, misconceptionId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.misconception_confidence_observation "
            + "WHERE attempt_id = ? AND misconception_id = ?",
        attemptId, misconceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aPersistedProvenanceRowIsImmutable() {
    wire();
    UUID misconceptionId = newPublishedMisconception("immutable provenance fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-immutable-provenance");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));
    UUID confidenceObservationId =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionId).orElseThrow().id();

    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.misconception_confidence_observation_evidence "
            + "WHERE confidence_observation_id = ?",
        confidenceObservationId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void provenanceGuardRejectsAnEvidenceObservationFromADifferentMisconception() {
    wire();
    UUID misconceptionOne = newPublishedMisconception("provenance mismatch fixture one");
    UUID misconceptionTwo = newPublishedMisconception("provenance mismatch fixture two");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionOne);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionTwo);
    Learner learner = learners.provisionForSubject("confidence-provenance-mismatch");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));

    UUID confidenceObservationId =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionOne).orElseThrow().id();
    UUID evidenceFromTheOtherMisconception = evidenceObservations
        .findByResponseAndMisconception(onlyResponseIdFor(attemptId, ACKS_MCQ_I2), misconceptionTwo)
        .orElseThrow().id();

    assertThatThrownBy(() -> confidenceRepository.insertProvenance(
        confidenceObservationId, evidenceFromTheOtherMisconception))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void exactProvenanceReconstructsPreciselyTheContributingEvidence() {
    wire();
    UUID misconceptionId = newPublishedMisconception("exact reconstruction fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-exact-reconstruction");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));

    UUID responseOne = onlyResponseIdFor(attemptId, ACKS_MCQ_A1);
    UUID responseTwo = onlyResponseIdFor(attemptId, ACKS_MCQ_I2);
    UUID evidenceOne =
        evidenceObservations.findByResponseAndMisconception(responseOne, misconceptionId).orElseThrow().id();
    UUID evidenceTwo =
        evidenceObservations.findByResponseAndMisconception(responseTwo, misconceptionId).orElseThrow().id();

    MisconceptionConfidenceObservation snapshot =
        confidenceRepository.findByAttemptAndMisconception(attemptId, misconceptionId).orElseThrow();
    assertThat(confidenceRepository.findProvenanceFor(snapshot.id()))
        .containsExactlyInAnyOrder(evidenceOne, evidenceTwo);
    assertThat(snapshot.supportingCount() + snapshot.contradictoryCount() + snapshot.inconclusiveCount())
        .isEqualTo(2);
  }

  @Test
  void confidenceWriteFailureRollsBackTheWholeSubmissionTransaction() throws SQLException {
    wire();
    UUID misconceptionId = newPublishedMisconception("rollback fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-capture-rollback");
    UUID attemptId = freshAttempt(learner.id());

    revokeConfidenceInsert();
    try {
      assertThatThrownBy(() -> submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A")))
          .isInstanceOf(RuntimeException.class);
    } finally {
      grantConfidenceInsert();
    }

    assertThat(responseRowCountFor(attemptId)).isZero();
    assertThat(attemptStatus(attemptId)).isEqualTo("IN_PROGRESS");
  }

  // -------------------------------------------------------------------------------------------
  // The mandatory historical-snapshot test: an older snapshot is bound to its own evidence set
  // forever, and is never revisited when a later submission adds more evidence for the same
  // misconception.
  // -------------------------------------------------------------------------------------------

  @Test
  void anOlderSnapshotRemainsBoundToItsOwnEvidenceAfterLaterAttemptsAddMoreEvidence() {
    wire();
    UUID misconceptionId = newPublishedMisconception("historical snapshot fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("confidence-historical-snapshot");

    // Attempt A1: produces E1 (ACKS_MCQ_A1) and E2 (ACKS_MCQ_I2) -> snapshot S1.
    UUID attemptA1 = freshAttempt(learner.id());
    submit(learner.subject(), attemptA1, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));
    UUID e1 = evidenceObservations
        .findByResponseAndMisconception(onlyResponseIdFor(attemptA1, ACKS_MCQ_A1), misconceptionId)
        .orElseThrow().id();
    UUID e2 = evidenceObservations
        .findByResponseAndMisconception(onlyResponseIdFor(attemptA1, ACKS_MCQ_I2), misconceptionId)
        .orElseThrow().id();

    MisconceptionConfidenceObservation s1 =
        confidenceRepository.findByAttemptAndMisconception(attemptA1, misconceptionId).orElseThrow();
    assertThat(s1.supportingCount()).isEqualTo(2);
    assertThat(s1.band()).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(confidenceRepository.findProvenanceFor(s1.id())).containsExactlyInAnyOrder(e1, e2);

    // Attempt A2: produces E3 (ACKS_MCQ_A1, a fresh attempt reanswering the same real item) and E4
    // (ACKS_MCQ_I2) -> snapshot S2, over the complete accumulated set {E1, E2, E3, E4}.
    UUID attemptA2 = freshAttempt(learner.id());
    submit(learner.subject(), attemptA2, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));
    UUID e3 = evidenceObservations
        .findByResponseAndMisconception(onlyResponseIdFor(attemptA2, ACKS_MCQ_A1), misconceptionId)
        .orElseThrow().id();
    UUID e4 = evidenceObservations
        .findByResponseAndMisconception(onlyResponseIdFor(attemptA2, ACKS_MCQ_I2), misconceptionId)
        .orElseThrow().id();

    MisconceptionConfidenceObservation s2 =
        confidenceRepository.findByAttemptAndMisconception(attemptA2, misconceptionId).orElseThrow();
    assertThat(s2.supportingCount()).isEqualTo(4);
    assertThat(s2.band()).isEqualTo(DiagnosticConfidenceBand.HIGH);
    assertThat(confidenceRepository.findProvenanceFor(s2.id())).containsExactlyInAnyOrder(e1, e2, e3, e4);

    // The crux: re-read S1 after A2 has run. It must be byte-for-byte what it was before --
    // never touched, never recomputed, never grown to include E3/E4.
    MisconceptionConfidenceObservation s1After =
        confidenceRepository.findById(s1.id()).orElseThrow();
    assertThat(s1After).isEqualTo(s1);
    assertThat(s1After.supportingCount()).isEqualTo(2);
    assertThat(s1After.band()).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(confidenceRepository.findProvenanceFor(s1After.id())).containsExactlyInAnyOrder(e1, e2);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID newPublishedMisconception(String name) {
    UUID id = UUID.randomUUID();
    misconceptions.insertTargetingObjective(
        id, name, "granular confidence fixture", ACKS_DURABILITY_TRADEOFFS);
    misconceptions.publish(id);
    return id;
  }

  private void mapAndPublish(UUID itemVersionId, String optionId, UUID misconceptionId) {
    mappings.insert(itemVersionId, optionId, misconceptionId);
    mappings.publish(itemVersionId, optionId, misconceptionId);
  }

  private UUID freshAttempt(UUID learnerId) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "confidence-fixture-" + attemptId);
    return attemptId;
  }

  private DiagnosticSubmissionRequest oneResponse(UUID itemVersionId, String selectedOption) {
    return new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(itemVersionId.toString(), List.of(selectedOption))));
  }

  private UUID onlyResponseIdFor(UUID attemptId, UUID itemVersionId) {
    return runtimeJdbc.queryForObject(
        "SELECT id FROM core.assessment_response WHERE attempt_id = ? AND item_version_id = ?",
        UUID.class, attemptId, itemVersionId);
  }

  private long confidenceObservationCountFor(UUID attemptId, UUID misconceptionId) {
    Long count = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.misconception_confidence_observation "
            + "WHERE attempt_id = ? AND misconception_id = ?",
        Long.class, attemptId, misconceptionId);
    return count == null ? 0 : count;
  }

  private long responseRowCountFor(UUID attemptId) {
    Long count = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_response WHERE attempt_id = ?", Long.class, attemptId);
    return count == null ? 0 : count;
  }

  private String attemptStatus(UUID attemptId) {
    return runtimeJdbc.queryForObject(
        "SELECT status FROM core.assessment_attempt WHERE id = ?", String.class, attemptId);
  }

  private void revokeConfidenceInsert() throws SQLException {
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("REVOKE INSERT ON core.misconception_confidence_observation FROM ramals_core_runtime");
    }
  }

  private void grantConfidenceInsert() throws SQLException {
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("GRANT INSERT ON core.misconception_confidence_observation TO ramals_core_runtime");
    }
  }

  private SubmissionResult submit(String subject, UUID attemptId, DiagnosticSubmissionRequest request) {
    MDC.put("interactionId", "01920000-0000-7000-8000-0000000000f2");
    try {
      return transactionTemplate.execute(
          status -> submissions.submit(subject, "KAFKA", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }
  }

  private void wire() {
    if (submissions == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();

      learners = new LearnerRepository(runtimeJdbc);
      misconceptions = new MisconceptionRepository(runtimeJdbc);
      mappings = new MisconceptionOptionMappingRepository(runtimeJdbc);
      evidenceObservations = new MisconceptionEvidenceObservationRepository(runtimeJdbc);
      confidenceRepository = new MisconceptionConfidenceRepository(runtimeJdbc);
      MisconceptionEvidenceCaptureService captureService =
          new MisconceptionEvidenceCaptureService(mappings, evidenceObservations);
      MisconceptionConfidenceService confidenceService =
          new MisconceptionConfidenceService(confidenceRepository, new DiagnosticConfidenceCalculatorV1());

      AssessmentRepository assessments = new AssessmentRepository(runtimeJdbc, mapper);
      LearnerService learnerService = new LearnerService(learners);
      MasteryRepository masteryRepository = new MasteryRepository(runtimeJdbc);
      EvidenceRepository evidenceRepository = new EvidenceRepository(runtimeJdbc);
      EvidenceService evidenceService = new EvidenceService(evidenceRepository);
      MasteryService masteryService = new MasteryService(
          masteryRepository, evidenceRepository, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculatorV2(), new MasteryStatusPolicyV2());
      RecommendationService recommendationService = new RecommendationService(
          new RecommendationPolicy(), new RecommendationRepository(runtimeJdbc), learnerService);
      DiagnosticConfidenceService diagnosticConfidenceService = new DiagnosticConfidenceService(
          new ProbeProvenanceRepository(runtimeJdbc), new DiagnosticConfidenceRepository(runtimeJdbc),
          new DiagnosticConfidenceCalculatorV1());

      submissions = new DiagnosticSubmissionService(assessments, learnerService,
          new DiagnosticScorerV2(), evidenceService, masteryService, recommendationService,
          diagnosticConfidenceService, captureService, confidenceService, mapper);
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
