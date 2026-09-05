package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceState;
import io.ramals.learningplatform.assessment.DiagnosticReport.MisconceptionFinding;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M2-ADR-029 (H6): the Granular Diagnostic Report against real PostgreSQL and the real,
 * already-seeded KAFKA v2 assessment bank -- the same fixture-over-real-flow discipline the G2/G3
 * suites already established. Submits through the real {@code DiagnosticSubmissionService} wherever
 * the scenario is about the report's own composition of already-governed facts, and reaches directly
 * into {@link MisconceptionEvidenceCaptureService} (bypassing G3's own recompute) for the one
 * scenario that specifically needs evidence with no confidence snapshot yet ({@code NOT_ASSESSED}).
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticReportPersistenceIntegrationTests {

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
  private DiagnosticNodeRepository nodes;
  private MisconceptionEvidenceObservationRepository evidenceObservations;
  private MisconceptionEvidenceCaptureService captureService;
  private MisconceptionConfidenceRepository confidenceRepository;
  private DiagnosticReportService reportService;
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
  // Current-domain snapshot selection determinism.
  // -------------------------------------------------------------------------------------------

  @Test
  void currentDomainReportIsDeterministicUnderATimestampTie() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("tie fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("report-tie");

    // Two confidence rows for the same (learner, misconception), forced to share the exact same
    // created_at by inserting both within one transaction (CURRENT_TIMESTAMP is fixed per
    // transaction in PostgreSQL) -- a genuine tie, not merely a close two. Each cites real evidence
    // as its own provenance, self-consistently, to satisfy V059's deferred count-vs-provenance
    // constraint trigger (M2-ADR-028, hardened on review of PR #256).
    transactionTemplate.execute(status -> {
      // Responses may only be written while their own attempt is IN_PROGRESS, and only one
      // IN_PROGRESS attempt may exist per learner+assessment_version at a time -- so each attempt is
      // completed before the next is opened. created_at still ties across both confidence rows
      // below, since CURRENT_TIMESTAMP is fixed for the whole transaction regardless.
      UUID fakeAttemptOne = freshAttempt(learner.id());
      UUID responseOne = insertResponse(fakeAttemptOne, ACKS_MCQ_A1, "A", false);
      completeAttempt(fakeAttemptOne);
      UUID evidenceOne = evidenceObservations.insert(
          learner.id(), responseOne, misconceptionId, MisconceptionEvidenceOutcome.SUPPORTING,
          MisconceptionEvidenceCaptureService.POLICY);
      UUID confidenceOne = confidenceRepository.insert(fakeAttemptOne, learner.id(), misconceptionId,
          new DiagnosticConfidenceResult(DiagnosticConfidenceBand.LOW, 1, 0, 0,
              DiagnosticConfidenceCalculatorV1.POLICY_VERSION));
      confidenceRepository.insertProvenance(confidenceOne, evidenceOne);

      UUID fakeAttemptTwo = freshAttempt(learner.id());
      UUID responseTwo = insertResponse(fakeAttemptTwo, ACKS_MCQ_A1, "A", false);
      completeAttempt(fakeAttemptTwo);
      UUID evidenceTwo = evidenceObservations.insert(
          learner.id(), responseTwo, misconceptionId, MisconceptionEvidenceOutcome.SUPPORTING,
          MisconceptionEvidenceCaptureService.POLICY);
      UUID confidenceTwo = confidenceRepository.insert(fakeAttemptTwo, learner.id(), misconceptionId,
          new DiagnosticConfidenceResult(DiagnosticConfidenceBand.MODERATE, 2, 0, 0,
              DiagnosticConfidenceCalculatorV1.POLICY_VERSION));
      confidenceRepository.insertProvenance(confidenceTwo, evidenceOne);
      confidenceRepository.insertProvenance(confidenceTwo, evidenceTwo);
      return null;
    });

    DiagnosticReport firstRead = reportService.currentDomainReportForLearner(learner.id(), "KAFKA");
    DiagnosticReport secondRead = reportService.currentDomainReportForLearner(learner.id(), "KAFKA");

    MisconceptionFinding firstFinding = onlyFindingFor(firstRead, misconceptionId);
    MisconceptionFinding secondFinding = onlyFindingFor(secondRead, misconceptionId);
    // Deterministic: repeated reads under a genuine created_at tie always resolve to the exact
    // same row (the same secondary id-ordering tiebreak every time), never a different one per call.
    assertThat(secondFinding.confidence().band()).isEqualTo(firstFinding.confidence().band());
    assertThat(secondFinding.evidenceSummary().supportingCount())
        .isEqualTo(firstFinding.evidenceSummary().supportingCount());
  }

  // -------------------------------------------------------------------------------------------
  // Attempt Diagnostic Report scope and historical stability.
  // -------------------------------------------------------------------------------------------

  @Test
  void attemptReportContainsOnlyWhatThatAttemptTouched() {
    wire();
    UUID misconceptionOne = newPublishedMisconceptionTargetingObjective("attempt-scope fixture one");
    UUID misconceptionTwo = newPublishedMisconceptionTargetingObjective("attempt-scope fixture two");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionOne);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionTwo);
    Learner learner = learners.provisionForSubject("report-attempt-scope");

    UUID attemptOne = freshAttempt(learner.id());
    submit(learner.subject(), attemptOne, oneResponse(ACKS_MCQ_A1, "A"));
    UUID attemptTwo = freshAttempt(learner.id());
    submit(learner.subject(), attemptTwo, oneResponse(ACKS_MCQ_I2, "A"));

    DiagnosticReport attemptTwoReport = reportService.attemptReportForLearner(learner.id(), attemptTwo.toString());

    // Scoped to misconceptionTwo specifically -- ACKS_MCQ_I2's option "A" is a real, shared item
    // other tests in this suite also map their own misconceptions onto, so attemptTwo's own report
    // may legitimately include those too (correct G2/G3 behavior); what this test must prove is that
    // misconceptionOne (evidenced only by the EARLIER, different attemptOne) is absent.
    assertThat(attemptTwoReport.misconceptionFindings())
        .extracting(MisconceptionFinding::misconceptionId)
        .contains(misconceptionTwo)
        .doesNotContain(misconceptionOne);
  }

  @Test
  void anEarlierAttemptReportRemainsStableAfterALaterAttemptAddsEvidence() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("attempt-stability fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("report-attempt-stability");

    UUID attemptOne = freshAttempt(learner.id());
    submit(learner.subject(), attemptOne, oneResponse(ACKS_MCQ_A1, "A"));
    DiagnosticReport reportBefore = reportService.attemptReportForLearner(learner.id(), attemptOne.toString());
    int supportingBefore = onlyFindingFor(reportBefore, misconceptionId).evidenceSummary().supportingCount();
    assertThat(supportingBefore).isEqualTo(1);

    UUID attemptTwo = freshAttempt(learner.id());
    submit(learner.subject(), attemptTwo, oneResponse(ACKS_MCQ_I2, "A"));

    DiagnosticReport reportAfter = reportService.attemptReportForLearner(learner.id(), attemptOne.toString());
    assertThat(onlyFindingFor(reportAfter, misconceptionId).evidenceSummary().supportingCount())
        .isEqualTo(supportingBefore);
  }

  // -------------------------------------------------------------------------------------------
  // Ontology projection: exact ancestry per target type, never a fabricated level.
  // -------------------------------------------------------------------------------------------

  @Test
  void objectiveTargetedMisconceptionHasNoConceptOrSubConceptContext() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("objective-target fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("report-objective-target");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    MisconceptionFinding finding = onlyFindingFor(
        reportService.currentDomainReportForLearner(learner.id(), "KAFKA"), misconceptionId);

    assertThat(finding.targetType()).isEqualTo(MisconceptionTargetType.LEARNING_OBJECTIVE);
    // targetId is the OBJECTIVE's own id -- never the misconception's own id, and never fabricated.
    assertThat(finding.targetId()).isEqualTo(ACKS_DURABILITY_TRADEOFFS);
    assertThat(finding.objectiveContext()).isNotNull();
    assertThat(finding.objectiveContext().objectiveId()).isEqualTo(ACKS_DURABILITY_TRADEOFFS);
    assertThat(finding.conceptContext()).isNull();
    assertThat(finding.subConceptContext()).isNull();
  }

  @Test
  void conceptTargetedMisconceptionHasObjectiveAndConceptContextOnly() {
    wire();
    UUID conceptId = newPublishedConcept("concept-target fixture concept");
    UUID misconceptionId = newPublishedMisconceptionTargetingNode("concept-target fixture", conceptId);
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("report-concept-target");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    MisconceptionFinding finding = onlyFindingFor(
        reportService.currentDomainReportForLearner(learner.id(), "KAFKA"), misconceptionId);

    assertThat(finding.targetType()).isEqualTo(MisconceptionTargetType.CONCEPT);
    assertThat(finding.targetId()).isEqualTo(conceptId);
    assertThat(finding.objectiveContext().objectiveId()).isEqualTo(ACKS_DURABILITY_TRADEOFFS);
    assertThat(finding.conceptContext()).isNotNull();
    assertThat(finding.conceptContext().conceptId()).isEqualTo(conceptId);
    assertThat(finding.subConceptContext()).isNull();
  }

  @Test
  void subConceptTargetedMisconceptionHasFullAncestry() {
    wire();
    UUID conceptId = newPublishedConcept("sub-concept-target fixture concept");
    UUID subConceptId = newPublishedSubConcept("sub-concept-target fixture sub-concept", conceptId);
    UUID misconceptionId = newPublishedMisconceptionTargetingNode("sub-concept-target fixture", subConceptId);
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("report-subconcept-target");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    MisconceptionFinding finding = onlyFindingFor(
        reportService.currentDomainReportForLearner(learner.id(), "KAFKA"), misconceptionId);

    assertThat(finding.targetType()).isEqualTo(MisconceptionTargetType.SUB_CONCEPT);
    assertThat(finding.targetId()).isEqualTo(subConceptId);
    assertThat(finding.objectiveContext().objectiveId()).isEqualTo(ACKS_DURABILITY_TRADEOFFS);
    assertThat(finding.conceptContext()).isNotNull();
    assertThat(finding.conceptContext().conceptId()).isEqualTo(conceptId);
    assertThat(finding.subConceptContext()).isNotNull();
    assertThat(finding.subConceptContext().subConceptId()).isEqualTo(subConceptId);
  }

  // -------------------------------------------------------------------------------------------
  // Misconception identity, zero-evidence absence, and domain-level NO_EVIDENCE.
  // -------------------------------------------------------------------------------------------

  @Test
  void twoMisconceptionsWithTheSameNameRemainDistinctFindings() {
    wire();
    String sharedName = "acks=all guarantees durability regardless of min.insync.replicas";
    UUID misconceptionOne = newPublishedMisconceptionTargetingObjective(sharedName);
    UUID misconceptionTwo = newPublishedMisconceptionTargetingObjective(sharedName);
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionOne);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionTwo);
    Learner learner = learners.provisionForSubject("report-identity-distinct");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));

    DiagnosticReport report = reportService.currentDomainReportForLearner(learner.id(), "KAFKA");

    assertThat(report.misconceptionFindings())
        .extracting(MisconceptionFinding::misconceptionId)
        .contains(misconceptionOne, misconceptionTwo);
    assertThat(misconceptionOne).isNotEqualTo(misconceptionTwo);
  }

  @Test
  void anAuthoredMisconceptionWithNoLearnerEvidenceIsAbsentFromFindings() {
    wire();
    UUID evidencedMisconception = newPublishedMisconceptionTargetingObjective("evidenced fixture");
    UUID unevidencedMisconception = newPublishedMisconceptionTargetingObjective("never evidenced fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", evidencedMisconception);
    mapAndPublish(ACKS_MCQ_I2, "A", unevidencedMisconception); // published, but never answered
    Learner learner = learners.provisionForSubject("report-zero-evidence-absent");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    DiagnosticReport report = reportService.currentDomainReportForLearner(learner.id(), "KAFKA");

    assertThat(report.misconceptionFindings())
        .extracting(MisconceptionFinding::misconceptionId)
        .contains(evidencedMisconception)
        .doesNotContain(unevidencedMisconception);
  }

  @Test
  void noEvidenceInDomainYieldsNoEvidenceStatusAndEmptyFindings() {
    wire();
    Learner learner = learners.provisionForSubject("report-no-evidence-domain");

    DiagnosticReport report = reportService.currentDomainReportForLearner(learner.id(), "KAFKA");

    assertThat(report.diagnosticDataStatus()).isEqualTo(DiagnosticReport.DiagnosticDataStatus.NO_EVIDENCE);
    assertThat(report.misconceptionFindings()).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // NOT_ASSESSED vs. INSUFFICIENT_EVIDENCE -- never blurred.
  // -------------------------------------------------------------------------------------------

  @Test
  void evidenceWithoutAConfidenceSnapshotIsReportedAsNotAssessed() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("not-assessed fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("report-not-assessed");
    UUID attemptId = freshAttempt(learner.id());
    // Raw response + direct G2 capture, bypassing the full submission flow so G3 never recomputes.
    insertResponse(attemptId, ACKS_MCQ_A1, "A", false);
    captureService.captureEvidence(learner.id(), attemptId, ACKS_MCQ_A1);

    MisconceptionFinding finding = onlyFindingFor(
        reportService.currentDomainReportForLearner(learner.id(), "KAFKA"), misconceptionId);

    assertThat(finding.confidenceState()).isEqualTo(ConfidenceState.NOT_ASSESSED);
    assertThat(finding.confidence()).isNull();
    assertThat(finding.evidenceSummary().supportingCount()).isEqualTo(1);
  }

  @Test
  void inconclusiveOnlyEvidenceIsAssessedAsInsufficientEvidenceNotNotAssessed() {
    wire();
    // Mapped only to "C" -- a wrong-but-untagged "A" answer is INCONCLUSIVE, not SUPPORTING, and a
    // real G3 recompute still runs (through the full submission flow), producing a real snapshot.
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("insufficient-evidence fixture");
    mapAndPublish(ACKS_MCQ_A1, "C", misconceptionId);
    Learner learner = learners.provisionForSubject("report-insufficient-evidence");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    MisconceptionFinding finding = onlyFindingFor(
        reportService.currentDomainReportForLearner(learner.id(), "KAFKA"), misconceptionId);

    assertThat(finding.confidenceState()).isEqualTo(ConfidenceState.ASSESSED);
    assertThat(finding.confidence()).isNotNull();
    assertThat(finding.confidence().band()).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
    assertThat(finding.evidenceSummary().inconclusiveCount()).isEqualTo(1);
    assertThat(finding.evidenceSummary().supportingCount()).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // Exact band passthrough (no recalculation) and evidence-count integrity.
  // -------------------------------------------------------------------------------------------

  @Test
  void reportedBandsExactlyMatchPersistedGranularConfidenceSnapshotsAndCountsAreNotRecomputed() {
    wire();
    UUID misconceptionLow = newPublishedMisconceptionTargetingObjective("band fixture low");
    UUID misconceptionModerate = newPublishedMisconceptionTargetingObjective("band fixture moderate");
    UUID misconceptionHigh = newPublishedMisconceptionTargetingObjective("band fixture high");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionLow);
    mapAndPublish(ACKS_MCQ_A1, "C", misconceptionModerate);
    mapAndPublish(ACKS_MCQ_A1, "D", misconceptionHigh);
    Learner learner = learners.provisionForSubject("report-band-passthrough");

    submitWrongAnswer(learner, "A"); // 1 supporting for "low"
    submitWrongAnswer(learner, "C"); // 2 supporting for "moderate"
    submitWrongAnswer(learner, "C");
    submitWrongAnswer(learner, "D"); // 3 supporting for "high"
    submitWrongAnswer(learner, "D");
    submitWrongAnswer(learner, "D");

    DiagnosticReport report = reportService.currentDomainReportForLearner(learner.id(), "KAFKA");

    MisconceptionFinding low = onlyFindingFor(report, misconceptionLow);
    MisconceptionFinding moderate = onlyFindingFor(report, misconceptionModerate);
    MisconceptionFinding high = onlyFindingFor(report, misconceptionHigh);
    assertThat(low.confidence().band()).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(low.evidenceSummary().supportingCount()).isEqualTo(1);
    assertThat(moderate.confidence().band()).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(moderate.evidenceSummary().supportingCount()).isEqualTo(2);
    assertThat(high.confidence().band()).isEqualTo(DiagnosticConfidenceBand.HIGH);
    assertThat(high.evidenceSummary().supportingCount()).isEqualTo(3);

    // Exact integrity: the report's own counts equal the persisted snapshot's own counts, read via
    // the repository directly -- never a live recount for an ASSESSED finding.
    MisconceptionConfidenceObservation persistedHigh =
        confidenceRepository.findLatestFor(learner.id(), misconceptionHigh).orElseThrow();
    assertThat(high.evidenceSummary().supportingCount()).isEqualTo(persistedHigh.supportingCount());
  }

  // -------------------------------------------------------------------------------------------
  // Learner and domain isolation.
  // -------------------------------------------------------------------------------------------

  @Test
  void twoLearnersWithTheSameMisconceptionAreFullyIsolated() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("learner-isolation fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learnerOne = learners.provisionForSubject("report-isolation-learner-one");
    Learner learnerTwo = learners.provisionForSubject("report-isolation-learner-two");
    UUID attemptOne = freshAttempt(learnerOne.id());
    submit(learnerOne.subject(), attemptOne, oneResponse(ACKS_MCQ_A1, "A"));

    DiagnosticReport reportOne = reportService.currentDomainReportForLearner(learnerOne.id(), "KAFKA");
    DiagnosticReport reportTwo = reportService.currentDomainReportForLearner(learnerTwo.id(), "KAFKA");

    assertThat(reportOne.misconceptionFindings())
        .extracting(MisconceptionFinding::misconceptionId).contains(misconceptionId);
    assertThat(reportTwo.misconceptionFindings()).isEmpty();
    assertThat(reportTwo.diagnosticDataStatus()).isEqualTo(DiagnosticReport.DiagnosticDataStatus.NO_EVIDENCE);
  }

  @Test
  void aKafkaDomainReportExcludesAMisconceptionTargetingAnotherDomainsObjective() {
    wire();
    UUID otherDomainObjectiveId = createObjectiveInFreshDomain("REPORTOTHER");
    UUID kafkaMisconception = newPublishedMisconceptionTargetingObjective("domain-isolation kafka fixture");
    UUID otherDomainMisconception = UUID.randomUUID();
    misconceptions.insertTargetingObjective(
        otherDomainMisconception, "domain-isolation other fixture", "targets a different domain",
        otherDomainObjectiveId);
    misconceptions.publish(otherDomainMisconception);
    mapAndPublish(ACKS_MCQ_A1, "A", kafkaMisconception);
    mapAndPublish(ACKS_MCQ_I2, "A", otherDomainMisconception);
    Learner learner = learners.provisionForSubject("report-domain-isolation");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("A")))));

    DiagnosticReport report = reportService.currentDomainReportForLearner(learner.id(), "KAFKA");

    assertThat(report.misconceptionFindings())
        .extracting(MisconceptionFinding::misconceptionId)
        .contains(kafkaMisconception)
        .doesNotContain(otherDomainMisconception);
  }

  // -------------------------------------------------------------------------------------------
  // Read-only behavior.
  // -------------------------------------------------------------------------------------------

  @Test
  void repeatedReportReadsWriteNothing() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("read-only fixture");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("report-read-only");
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, "A"));

    long confidenceCountBefore = countRows("core.misconception_confidence_observation");
    long evidenceCountBefore = countRows("core.misconception_evidence_observation");
    long responseCountBefore = countRows("core.assessment_response");

    reportService.currentDomainReportForLearner(learner.id(), "KAFKA");
    reportService.currentDomainReportForLearner(learner.id(), "KAFKA");
    reportService.attemptReportForLearner(learner.id(), attemptId.toString());

    assertThat(countRows("core.misconception_confidence_observation")).isEqualTo(confidenceCountBefore);
    assertThat(countRows("core.misconception_evidence_observation")).isEqualTo(evidenceCountBefore);
    assertThat(countRows("core.assessment_response")).isEqualTo(responseCountBefore);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID newPublishedMisconceptionTargetingObjective(String name) {
    UUID id = UUID.randomUUID();
    misconceptions.insertTargetingObjective(id, name, "H6 report fixture", ACKS_DURABILITY_TRADEOFFS);
    misconceptions.publish(id);
    return id;
  }

  private UUID newPublishedMisconceptionTargetingNode(String name, UUID nodeId) {
    UUID id = UUID.randomUUID();
    misconceptions.insertTargetingNode(id, name, "H6 report fixture", nodeId);
    misconceptions.publish(id);
    return id;
  }

  private UUID newPublishedConcept(String name) {
    UUID id = UUID.randomUUID();
    nodes.insertConcept(id, ACKS_DURABILITY_TRADEOFFS, name, "H6 report fixture", nextDisplayOrder());
    nodes.publish(id);
    return id;
  }

  private UUID newPublishedSubConcept(String name, UUID parentConceptId) {
    UUID id = UUID.randomUUID();
    nodes.insertSubConcept(id, parentConceptId, name, "H6 report fixture", nextDisplayOrder());
    nodes.publish(id);
    return id;
  }

  // Static: JUnit5 creates a fresh test instance per method, so a per-instance counter would
  // collide across methods against the same shared real objective's own UNIQUE(objective_id,
  // display_order).
  private static final java.util.concurrent.atomic.AtomicInteger DISPLAY_ORDER_COUNTER =
      new java.util.concurrent.atomic.AtomicInteger(100);

  private int nextDisplayOrder() {
    return DISPLAY_ORDER_COUNTER.getAndIncrement();
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
        """, attemptId, learnerId, ASSESSMENT_V2, "report-fixture-" + attemptId);
    return attemptId;
  }

  /** Marks a fixture attempt COMPLETED directly -- lets a second attempt for the same learner open
   * afterward without tripping uq_assessment_attempt_one_active's partial index (IN_PROGRESS only). */
  private void completeAttempt(UUID attemptId) {
    runtimeJdbc.update("UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
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

  private DiagnosticSubmissionRequest oneResponse(UUID itemVersionId, String selectedOption) {
    return new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(itemVersionId.toString(), List.of(selectedOption))));
  }

  private void submitWrongAnswer(Learner learner, String option) {
    UUID attemptId = freshAttempt(learner.id());
    submit(learner.subject(), attemptId, oneResponse(ACKS_MCQ_A1, option));
  }

  private MisconceptionFinding onlyFindingFor(DiagnosticReport report, UUID misconceptionId) {
    return report.misconceptionFindings().stream()
        .filter(finding -> finding.misconceptionId().equals(misconceptionId))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "No finding for misconception " + misconceptionId + " in report " + report));
  }

  private long countRows(String table) {
    Long count = runtimeJdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return count == null ? 0 : count;
  }

  /** A fresh domain/curriculum/skill/objective with no relation to KAFKA -- used only to prove
   * domain filtering is real, not merely assumed. */
  private UUID createObjectiveInFreshDomain(String domainCode) {
    UUID domainId = UUID.randomUUID();
    runtimeJdbc.update(
        "INSERT INTO core.learning_domain (id, code, name) VALUES (?, ?, ?)",
        domainId, domainCode, domainCode + " domain");

    // DRAFT first -- core.assert_curriculum_editable() blocks adding skills/objectives to an
    // already-published curriculum version, the same "publish only after authoring" ordering
    // core.protect_published_assessment_item enforces for assessment items.
    UUID curriculumVersionId = UUID.randomUUID();
    runtimeJdbc.update(
        "INSERT INTO core.curriculum_version (id, domain_id, version_code) VALUES (?, ?, 'v1')",
        curriculumVersionId, domainId);

    UUID skillId = UUID.randomUUID();
    runtimeJdbc.update(
        "INSERT INTO core.skill (id, domain_id, stable_code) VALUES (?, ?, ?)",
        skillId, domainId, domainCode + "_SKILL");

    UUID skillVersionId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.skill_version
          (id, skill_id, curriculum_version_id, title, description, difficulty,
           estimated_learning_minutes, display_order)
        VALUES (?, ?, ?, 'Other domain skill', 'A skill in an unrelated domain.', 'FOUNDATIONAL', 10, 1)
        """, skillVersionId, skillId, curriculumVersionId);

    UUID objectiveId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.learning_objective (id, skill_version_id, objective_code, description, display_order)
        VALUES (?, ?, ?, 'An objective in an unrelated domain.', 1)
        """, objectiveId, skillVersionId, domainCode + "_OBJECTIVE");

    // Published only now that its content exists -- a misconception targeting this objective must
    // be able to publish too (V057 refuses to publish a misconception whose target's curriculum is
    // still DRAFT).
    runtimeJdbc.update(
        "UPDATE core.curriculum_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = ?",
        curriculumVersionId);

    return objectiveId;
  }

  private SubmissionResult submit(String subject, UUID attemptId, DiagnosticSubmissionRequest request) {
    MDC.put("interactionId", "01920000-0000-7000-8000-0000000000f3");
    try {
      return transactionTemplate.execute(
          status -> submissions.submit(subject, "KAFKA", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }
  }

  private void wire() {
    if (reportService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();

      learners = new LearnerRepository(runtimeJdbc);
      misconceptions = new MisconceptionRepository(runtimeJdbc);
      mappings = new MisconceptionOptionMappingRepository(runtimeJdbc);
      nodes = new DiagnosticNodeRepository(runtimeJdbc);
      evidenceObservations = new MisconceptionEvidenceObservationRepository(runtimeJdbc);
      captureService = new MisconceptionEvidenceCaptureService(mappings, evidenceObservations);
      confidenceRepository = new MisconceptionConfidenceRepository(runtimeJdbc);
      MisconceptionConfidenceService misconceptionConfidenceService =
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
          diagnosticConfidenceService, captureService, misconceptionConfidenceService, mapper);
      transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));

      DiagnosticReportRepository reportRepository = new DiagnosticReportRepository(runtimeJdbc);
      reportService = new DiagnosticReportService(
          assessments, learnerService, reportRepository, confidenceRepository, masteryRepository);
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
