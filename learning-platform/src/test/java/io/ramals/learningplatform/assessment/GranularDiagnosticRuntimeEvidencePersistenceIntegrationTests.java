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
 * Granular diagnostic runtime evidence capture (M2-ADR-027) against real PostgreSQL and the real,
 * already-seeded KAFKA v2 curriculum/bank -- the same fixture-over-real-flow pattern
 * {@code GranularDiagnosticOntologyPersistenceIntegrationTests} already established for #254,
 * extended one step further: most scenarios here exercise {@link MisconceptionEvidenceCaptureService}
 * and the two new tables' guard triggers directly, and the two scenarios that specifically depend on
 * {@code DiagnosticSubmissionService}'s own resubmission and transactional semantics
 * ({@link #aDuplicateSubmissionProducesNoAdditionalObservations()},
 * {@link #captureFailureRollsBackTheWholeSubmissionTransaction()}) go through the real submission
 * flow end to end.
 *
 * <p>Every scenario reuses the real, already-seeded ACKS_MCQ_A1 item (options A, B, C, D; correct:
 * B), authoring fresh, test-scoped misconceptions and mappings against it rather than inventing new
 * content -- the same "real content, no invented ids" discipline #254's own foundation tests used.
 * The real seeded mapping (option A -&gt; MISCONCEPTION_ACKS_ALL_ALONE, published since V057) is left
 * alone; it may itself also produce evidence when a test's response selects "A", which is harmless
 * and simply not asserted on.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class GranularDiagnosticRuntimeEvidencePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");

  // Real, already-published (since V052/V057) vertical slice.
  private static final UUID ACKS_DURABILITY_TRADEOFFS = UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625"); // correct: B
  // A real SINGLE_CHOICE item that carries no misconception mapping at all.
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624");
  private static final UUID ACKS_FILL_F = UUID.fromString("01900000-0000-7000-8000-000000000627"); // FILL_BLANK

  private static String databaseUrl;
  private JdbcTemplate runtimeJdbc;
  private LearnerRepository learners;
  private MisconceptionRepository misconceptions;
  private MisconceptionOptionMappingRepository mappings;
  private MisconceptionEvidenceObservationRepository observations;
  private MisconceptionEvidenceCaptureService captureService;
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
  // Truth table: SUPPORTING, CONTRADICTORY, INCONCLUSIVE.
  // -------------------------------------------------------------------------------------------

  @Test
  void aWrongAnswerTaggedToTheEligibleMisconceptionIsSupportingEvidence() {
    wire();
    UUID misconceptionId = newPublishedMisconception("supporting fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-supporting").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);

    MisconceptionEvidenceObservation observation =
        observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow();
    assertThat(observation.outcome()).isEqualTo(MisconceptionEvidenceOutcome.SUPPORTING);
    assertThat(observation.learnerId()).isEqualTo(learnerId);
    assertThat(observation.responseId()).isEqualTo(responseId);
    assertThat(observation.policyVersion()).isEqualTo(MisconceptionEvidenceCaptureService.POLICY);
  }

  @Test
  void aCorrectAnswerIsContradictoryForEveryEligibleMisconception() {
    wire();
    UUID misconceptionId = newPublishedMisconception("contradictory fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-contradictory").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "B", true);

    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);

    assertThat(observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow().outcome())
        .isEqualTo(MisconceptionEvidenceOutcome.CONTRADICTORY);
  }

  @Test
  void aDifferentWrongOptionIsInconclusiveForAnEligibleButUntaggedMisconception() {
    wire();
    UUID misconceptionId = newPublishedMisconception("inconclusive fixture");
    mapAndPublish(ACKS_MCQ_A1, "C", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-inconclusive").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);

    assertThat(observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow().outcome())
        .isEqualTo(MisconceptionEvidenceOutcome.INCONCLUSIVE);
  }

  // -------------------------------------------------------------------------------------------
  // Evaluate every event-time-eligible misconception; complete provenance.
  // -------------------------------------------------------------------------------------------

  @Test
  void oneResponseProducesEvidenceForEveryEventTimeEligibleMisconception() {
    wire();
    UUID m1 = newPublishedMisconception("multi-eligible m1");
    UUID m2 = newPublishedMisconception("multi-eligible m2");
    mapAndPublish(ACKS_MCQ_A1, "A", m1);
    mapAndPublish(ACKS_MCQ_A1, "C", m2);
    UUID learnerId = learners.provisionForSubject("runtime-multi-eligible").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);

    assertThat(observations.findByResponseAndMisconception(responseId, m1).orElseThrow().outcome())
        .isEqualTo(MisconceptionEvidenceOutcome.SUPPORTING);
    assertThat(observations.findByResponseAndMisconception(responseId, m2).orElseThrow().outcome())
        .isEqualTo(MisconceptionEvidenceOutcome.INCONCLUSIVE);
  }

  @Test
  void multipleMappingsTaggingTheSameMisconceptionStillYieldExactlyOneObservation() {
    wire();
    UUID misconceptionId = newPublishedMisconception("multi-mapping fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_A1, "C", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-multi-mapping").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);

    assertThat(observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow().outcome())
        .isEqualTo(MisconceptionEvidenceOutcome.SUPPORTING);
    // Scoped to (response, misconception) rather than a blanket count for the response -- the
    // shared real item also accumulates other tests' own published "A"-tagged misconceptions
    // (and the real seeded one), each legitimately eligible for this same response too.
    assertThat(observationRowCountFor(responseId, misconceptionId)).isEqualTo(1);
  }

  @Test
  void observationProvenanceRecordsTheCompleteEventTimeEligibleMappingSetNotJustTheSelectedOption() {
    wire();
    UUID misconceptionId = newPublishedMisconception("complete provenance fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_A1, "C", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-complete-provenance").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);

    UUID observationId =
        observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow().id();
    List<MisconceptionEvidenceObservationRepository.ProvenanceRow> provenance =
        observations.findProvenanceFor(observationId);
    assertThat(provenance)
        .extracting(MisconceptionEvidenceObservationRepository.ProvenanceRow::optionId)
        .containsExactlyInAnyOrder("A", "C");
    assertThat(provenance)
        .allSatisfy(row -> assertThat(row.misconceptionId()).isEqualTo(misconceptionId));
  }

  // -------------------------------------------------------------------------------------------
  // Event-time semantics: a mapping published after the response it would be cited against.
  // -------------------------------------------------------------------------------------------

  @Test
  void aMappingPublishedAfterTheResponseCannotBeCitedAsProvenanceForAnEarlierObservation() {
    wire();
    UUID misconceptionId = newPublishedMisconception("late mapping fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-late-mapping").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);
    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);
    UUID observationId =
        observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow().id();

    // A second mapping for the SAME misconception, on a different option, published only now --
    // strictly after this response's own created_at.
    mapAndPublish(ACKS_MCQ_A1, "D", misconceptionId);

    assertThatThrownBy(() -> observations.insertProvenance(observationId, ACKS_MCQ_A1, "D", misconceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // DB invariants: the observation guard independently re-derives the expected outcome.
  // -------------------------------------------------------------------------------------------

  @Test
  void anObservationWithAnIncorrectlyDerivedOutcomeIsRejected() {
    wire();
    UUID misconceptionId = newPublishedMisconception("wrong outcome fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-wrong-outcome").id();
    UUID attemptId = freshAttempt(learnerId);
    // Event-time-derived outcome is SUPPORTING (wrong, tagged option selected) -- claiming
    // CONTRADICTORY instead must be rejected.
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    assertThatThrownBy(() -> observations.insert(
        learnerId, responseId, misconceptionId, MisconceptionEvidenceOutcome.CONTRADICTORY,
        MisconceptionEvidenceCaptureService.POLICY))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void anObservationForAMisconceptionWithNoEventTimeEligibleMappingIsRejected() {
    wire();
    UUID misconceptionId = newPublishedMisconception("no mapping fixture"); // never mapped
    UUID learnerId = learners.provisionForSubject("runtime-no-mapping").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    assertThatThrownBy(() -> observations.insert(
        learnerId, responseId, misconceptionId, MisconceptionEvidenceOutcome.INCONCLUSIVE,
        MisconceptionEvidenceCaptureService.POLICY))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void anObservationWhoseLearnerDoesNotOwnTheResponsesAttemptIsRejected() {
    wire();
    UUID misconceptionId = newPublishedMisconception("learner mismatch fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-learner-owner").id();
    UUID otherLearnerId = learners.provisionForSubject("runtime-learner-other").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    assertThatThrownBy(() -> observations.insert(
        otherLearnerId, responseId, misconceptionId, MisconceptionEvidenceOutcome.SUPPORTING,
        MisconceptionEvidenceCaptureService.POLICY))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void anObservationWithAnyPolicyVersionOtherThanV1IsRejected() {
    wire();
    UUID misconceptionId = newPublishedMisconception("wrong policy fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-wrong-policy").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);

    assertThatThrownBy(() -> observations.insert(
        learnerId, responseId, misconceptionId, MisconceptionEvidenceOutcome.SUPPORTING,
        "MISCONCEPTION_EVIDENCE_V0"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aDuplicateObservationForTheSameResponseAndMisconceptionIsRejected() {
    wire();
    UUID misconceptionId = newPublishedMisconception("duplicate fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-duplicate").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);
    observations.insert(learnerId, responseId, misconceptionId, MisconceptionEvidenceOutcome.SUPPORTING,
        MisconceptionEvidenceCaptureService.POLICY);

    assertThatThrownBy(() -> observations.insert(
        learnerId, responseId, misconceptionId, MisconceptionEvidenceOutcome.SUPPORTING,
        MisconceptionEvidenceCaptureService.POLICY))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aPersistedObservationIsImmutable() {
    wire();
    UUID misconceptionId = newPublishedMisconception("immutable observation fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-immutable-observation").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);
    observations.insert(learnerId, responseId, misconceptionId, MisconceptionEvidenceOutcome.SUPPORTING,
        MisconceptionEvidenceCaptureService.POLICY);

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.misconception_evidence_observation SET outcome = 'CONTRADICTORY' "
            + "WHERE response_id = ? AND misconception_id = ?",
        responseId, misconceptionId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.misconception_evidence_observation WHERE response_id = ? AND misconception_id = ?",
        responseId, misconceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aPersistedProvenanceRowIsImmutable() {
    wire();
    UUID misconceptionId = newPublishedMisconception("immutable provenance fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-immutable-provenance").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);
    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);
    UUID observationId =
        observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow().id();

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.misconception_evidence_observation_mapping SET option_id = 'Z' WHERE observation_id = ?",
        observationId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.misconception_evidence_observation_mapping WHERE observation_id = ?", observationId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void provenanceCitingAMisconceptionDifferentFromItsOwnObservationIsRejected() {
    wire();
    UUID misconceptionId = newPublishedMisconception("mismatch observation fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    UUID learnerId = learners.provisionForSubject("runtime-provenance-mismatch").id();
    UUID attemptId = freshAttempt(learnerId);
    UUID responseId = insertResponse(attemptId, ACKS_MCQ_A1, "A", false);
    captureService.captureEvidence(learnerId, attemptId, ACKS_MCQ_A1);
    UUID observationId =
        observations.findByResponseAndMisconception(responseId, misconceptionId).orElseThrow().id();

    UUID unrelatedMisconceptionId = UUID.randomUUID();
    assertThatThrownBy(() -> observations.insertProvenance(
        observationId, ACKS_MCQ_A1, "A", unrelatedMisconceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Non-eligible and non-SINGLE_CHOICE responses produce zero observations.
  // -------------------------------------------------------------------------------------------

  @Test
  void nonEligibleAndNonSingleChoiceResponsesProduceZeroObservations() {
    wire();
    // Two distinct learners -- one active attempt per (learner, assessment_version) at a time is
    // itself a DB invariant (uq_assessment_attempt_one_active), unrelated to this test's own concern.
    UUID plainLearnerId = learners.provisionForSubject("runtime-non-eligible-plain").id();
    UUID plainAttemptId = freshAttempt(plainLearnerId);
    UUID plainResponseId = insertResponse(plainAttemptId, ACKS_MCQ_I2, "A", false);
    captureService.captureEvidence(plainLearnerId, plainAttemptId, ACKS_MCQ_I2);
    assertThat(anyObservationExistsForResponse(plainResponseId)).isFalse();

    UUID fillLearnerId = learners.provisionForSubject("runtime-non-eligible-fill").id();
    UUID fillAttemptId = freshAttempt(fillLearnerId);
    UUID fillResponseId = insertResponse(fillAttemptId, ACKS_FILL_F, "irrelevant", false);
    captureService.captureEvidence(fillLearnerId, fillAttemptId, ACKS_FILL_F);
    assertThat(anyObservationExistsForResponse(fillResponseId)).isFalse();
  }

  // -------------------------------------------------------------------------------------------
  // End to end, through the real submission flow: resubmission and transactional atomicity.
  // -------------------------------------------------------------------------------------------

  @Test
  void aDuplicateSubmissionProducesNoAdditionalObservations() {
    wire();
    UUID misconceptionId = newPublishedMisconception("duplicate submission fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("runtime-duplicate-submission");
    UUID attemptId = freshAttempt(learner.id());
    DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A"))));

    submit(learner.subject(), attemptId, request);
    UUID responseId = onlyResponseIdFor(attemptId, ACKS_MCQ_A1);
    assertThat(observations.findByResponseAndMisconception(responseId, misconceptionId)).isPresent();

    // Resubmitting an already-COMPLETED attempt returns the original result without rewriting
    // anything -- DiagnosticSubmissionService.submit's own COMPLETED branch never calls score()
    // again, so captureEvidence is never invoked a second time either.
    submit(learner.subject(), attemptId, request);

    assertThat(responseRowCountFor(attemptId)).isEqualTo(1);
    // Scoped to (response, misconception) -- see multipleMappingsTaggingTheSameMisconceptionStill...
    // for why a blanket per-response count would also see other tests' own published mappings.
    assertThat(observationRowCountFor(responseId, misconceptionId)).isEqualTo(1);
  }

  @Test
  void captureFailureRollsBackTheWholeSubmissionTransaction() throws SQLException {
    wire();
    UUID misconceptionId = newPublishedMisconception("rollback fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("runtime-capture-rollback");
    UUID attemptId = freshAttempt(learner.id());

    revokeObservationInsert();
    try {
      assertThatThrownBy(() -> submit(learner.subject(), attemptId, new DiagnosticSubmissionRequest(
          List.of(new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A"))))))
          .isInstanceOf(RuntimeException.class);
    } finally {
      grantObservationInsert();
    }

    // Not just the observation -- the entire submission transaction, including the response
    // itself, never committed: the same atomicity guarantee H5's own confidence write already
    // relies on (DiagnosticSubmissionService.score()'s single @Transactional method).
    assertThat(responseRowCountFor(attemptId)).isZero();
    assertThat(attemptStatus(attemptId)).isEqualTo("IN_PROGRESS");
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID newPublishedMisconception(String name) {
    UUID id = UUID.randomUUID();
    misconceptions.insertTargetingObjective(
        id, name, "runtime evidence capture fixture", ACKS_DURABILITY_TRADEOFFS);
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
        """, attemptId, learnerId, ASSESSMENT_V2, "runtime-evidence-fixture-" + attemptId);
    return attemptId;
  }

  private UUID insertResponse(UUID attemptId, UUID itemVersionId, String selectedOption, boolean isCorrect) {
    UUID responseId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, ?::jsonb, ?)
        """, responseId, attemptId, itemVersionId,
        "{\"selectedOptions\":[\"" + selectedOption + "\"]}", isCorrect);
    return responseId;
  }

  private UUID onlyResponseIdFor(UUID attemptId, UUID itemVersionId) {
    return runtimeJdbc.queryForObject(
        "SELECT id FROM core.assessment_response WHERE attempt_id = ? AND item_version_id = ?",
        UUID.class, attemptId, itemVersionId);
  }

  private long responseRowCountFor(UUID attemptId) {
    Long count = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_response WHERE attempt_id = ?", Long.class, attemptId);
    return count == null ? 0 : count;
  }

  private long observationRowCountFor(UUID responseId, UUID misconceptionId) {
    Long count = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.misconception_evidence_observation "
            + "WHERE response_id = ? AND misconception_id = ?",
        Long.class, responseId, misconceptionId);
    return count == null ? 0 : count;
  }

  private boolean anyObservationExistsForResponse(UUID responseId) {
    Long count = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.misconception_evidence_observation WHERE response_id = ?",
        Long.class, responseId);
    return count != null && count > 0;
  }

  private String attemptStatus(UUID attemptId) {
    return runtimeJdbc.queryForObject(
        "SELECT status FROM core.assessment_attempt WHERE id = ?", String.class, attemptId);
  }

  private void revokeObservationInsert() throws SQLException {
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("REVOKE INSERT ON core.misconception_evidence_observation FROM ramals_core_runtime");
    }
  }

  private void grantObservationInsert() throws SQLException {
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("GRANT INSERT ON core.misconception_evidence_observation TO ramals_core_runtime");
    }
  }

  private SubmissionResult submit(String subject, UUID attemptId, DiagnosticSubmissionRequest request) {
    MDC.put("interactionId", "01920000-0000-7000-8000-0000000000f1");
    try {
      return transactionTemplate.execute(
          status -> submissions.submit(subject, "KAFKA", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }
  }

  private void wire() {
    if (captureService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();

      learners = new LearnerRepository(runtimeJdbc);
      misconceptions = new MisconceptionRepository(runtimeJdbc);
      mappings = new MisconceptionOptionMappingRepository(runtimeJdbc);
      observations = new MisconceptionEvidenceObservationRepository(runtimeJdbc);
      captureService = new MisconceptionEvidenceCaptureService(mappings, observations);

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
          diagnosticConfidenceService, captureService, mapper);
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
