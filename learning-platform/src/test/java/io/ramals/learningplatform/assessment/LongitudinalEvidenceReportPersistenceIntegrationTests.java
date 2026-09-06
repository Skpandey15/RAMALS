package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport.LongitudinalEvidenceFinding;
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
 * M2-ADR-030 (H7): the Longitudinal Evidence Projection against real PostgreSQL and the real,
 * already-seeded KAFKA v2 assessment bank -- the same fixture-over-real-flow discipline the G2/G3/H6
 * suites already established. Deliberately self-contained (does not import or extend {@code
 * DiagnosticReportPersistenceIntegrationTests}): H7's own architectural boundary is H6 untouched, zero
 * shared surface, including at the test-infrastructure level. Submits through the real {@code
 * DiagnosticSubmissionService} for baseline/G3-covered scenarios, and reaches directly into {@link
 * MisconceptionEvidenceCaptureService} (bypassing G3's own recompute) for scenarios that specifically
 * need evidence with no accompanying G3 snapshot.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class LongitudinalEvidenceReportPersistenceIntegrationTests {

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
  private LongitudinalEvidenceService longitudinalService;
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

    // ASSESSMENT_V2 is seeded DRAFT -- every fixture in this suite submits against it directly, but
    // AssessmentRepository.findPublishedDiagnostic("KAFKA") only ever resolves a PUBLISHED version.
    // Same precedent as DiagnosticReportPersistenceIntegrationTests's own @BeforeAll step.
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("""
          UPDATE core.assessment_version
          SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
          WHERE id = '01900000-0000-7000-8000-000000000403'
          """);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Baseline eligibility -- supporting-only, contradictory-only, mixed; INSUFFICIENT_EVIDENCE never.
  // -------------------------------------------------------------------------------------------

  @Test
  void contradictoryOnlyBaselineIsEligibleAndLaterSupportingEvidenceYieldsSupportOnly() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-baseline-contradictory");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-baseline-contradictory");

    // Correct answer on an item already mapped to this misconception -> CONTRADICTORY evidence ->
    // a real S=0,C=1 G3 snapshot -- a valid, directional baseline despite being contradiction-only.
    UUID baselineAttempt = freshAttempt(learner.id());
    submit(learner.subject(), baselineAttempt, oneResponse(ACKS_MCQ_A1, "B"));

    LongitudinalEvidenceFinding beforeLater = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(beforeLater.dataStatus()).isEqualTo(LongitudinalDataStatus.HAS_BASELINE);
    assertThat(beforeLater.baseline().evidenceStrength()).isEqualTo(DiagnosticConfidenceBand.LOW);

    // Later, wrong answer on the same item -> SUPPORTING evidence.
    UUID laterAttempt = freshAttempt(learner.id());
    submit(learner.subject(), laterAttempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceFinding after = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(after.state()).isEqualTo(LongitudinalEvidenceState.LATER_SUPPORT_ONLY);
    assertThat(after.laterEvidence().supportingCount()).isEqualTo(1);
    assertThat(after.laterEvidence().contradictoryCount()).isZero();
  }

  @Test
  void supportingOnlyBaselineWithLaterContradictionYieldsContradictionOnly() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-baseline-supporting");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-baseline-supporting");

    UUID baselineAttempt = freshAttempt(learner.id());
    submit(learner.subject(), baselineAttempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceFinding beforeLater = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(beforeLater.baseline().evidenceStrength()).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(beforeLater.state()).isEqualTo(LongitudinalEvidenceState.NO_LATER_EVIDENCE);

    UUID laterAttempt = freshAttempt(learner.id());
    submit(learner.subject(), laterAttempt, oneResponse(ACKS_MCQ_A1, "B"));

    LongitudinalEvidenceFinding after = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(after.state()).isEqualTo(LongitudinalEvidenceState.LATER_CONTRADICTION_ONLY);
  }

  @Test
  void mixedBaselineFromOneSubmissionIsEligible() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-baseline-mixed");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-baseline-mixed");

    UUID attempt = freshAttempt(learner.id());
    // One submission, two responses: wrong+tagged (SUPPORTING) and correct (CONTRADICTORY) -- G3
    // recomputes once, after the whole per-response loop, over both -- a single mixed snapshot.
    submit(learner.subject(), attempt, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("B")))));

    LongitudinalEvidenceFinding finding = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(finding.dataStatus()).isEqualTo(LongitudinalDataStatus.HAS_BASELINE);
    assertThat(finding.baseline().evidenceStrength()).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(finding.state()).isEqualTo(LongitudinalEvidenceState.NO_LATER_EVIDENCE);
  }

  // -------------------------------------------------------------------------------------------
  // NO_BASELINE != NO_LATER_EVIDENCE.
  // -------------------------------------------------------------------------------------------

  @Test
  void existingMisconceptionWithNoEligibleBaselineIsNoBaselineNotNoLaterEvidence() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-no-baseline");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-no-baseline");

    LongitudinalEvidenceFinding finding = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));

    assertThat(finding.dataStatus()).isEqualTo(LongitudinalDataStatus.NO_BASELINE);
    assertThat(finding.state()).isNull();
    assertThat(finding.baseline()).isNull();
    assertThat(finding.laterEvidence().supportingCount()).isZero();
    assertThat(finding.laterEvidence().contradictoryCount()).isZero();
    assertThat(finding.laterEvidence().inconclusiveCount()).isZero();
    assertThat(finding.laterEvidenceObservationIds()).isEmpty();
    assertThat(finding.policyVersion()).isNull();
    // No G3 snapshot exists at all for this pair -> confidenceCoverage is null, never CURRENT.
    assertThat(finding.latestConfidence()).isNull();
    assertThat(finding.confidenceCoverage()).isNull();
  }

  @Test
  void unknownMisconceptionIdIsNotFound() {
    wire();
    Learner learner = learners.provisionForSubject("h7-unknown-misconception");
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            longitudinalService.misconceptionDetailForLearner(learner.id(), UUID.randomUUID().toString()))
        .isInstanceOf(MisconceptionNotFoundException.class);
  }

  @Test
  void unregisteredLearnerYieldsNoBaselineRatherThanAnError() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-unregistered-learner");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);

    LongitudinalEvidenceReport report =
        longitudinalService.misconceptionDetail("subject-never-provisioned", misconceptionId.toString());

    LongitudinalEvidenceFinding finding = onlyFindingFor(report);
    assertThat(finding.dataStatus()).isEqualTo(LongitudinalDataStatus.NO_BASELINE);
  }

  // -------------------------------------------------------------------------------------------
  // All five states, plus inconclusive-only and mixed later evidence via direct G2 capture.
  // -------------------------------------------------------------------------------------------

  @Test
  void laterInconclusiveOnlyEvidenceIsClassifiedCorrectly() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-later-inconclusive");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-later-inconclusive");

    UUID baselineAttempt = freshAttempt(learner.id());
    submit(learner.subject(), baselineAttempt, oneResponse(ACKS_MCQ_A1, "A"));

    // Later attempt, wrong answer on an option NOT tagged to this misconception -> INCONCLUSIVE.
    UUID laterAttempt = freshAttempt(learner.id());
    insertResponse(laterAttempt, ACKS_MCQ_A1, "Z", false);
    captureService.captureEvidence(learner.id(), laterAttempt, ACKS_MCQ_A1);

    LongitudinalEvidenceFinding finding = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(finding.state()).isEqualTo(LongitudinalEvidenceState.LATER_INCONCLUSIVE_ONLY);
    assertThat(finding.laterEvidence().inconclusiveCount()).isEqualTo(1);
    assertThat(finding.laterEvidence().supportingCount()).isZero();
    assertThat(finding.laterEvidence().contradictoryCount()).isZero();
  }

  @Test
  void laterMixedEvidenceIsClassifiedCorrectly() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-later-mixed");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-later-mixed");

    UUID baselineAttempt = freshAttempt(learner.id());
    submit(learner.subject(), baselineAttempt, oneResponse(ACKS_MCQ_A1, "A"));

    UUID laterAttempt = freshAttempt(learner.id());
    submit(learner.subject(), laterAttempt, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ACKS_MCQ_A1.toString(), List.of("A")),
        new ItemResponse(ACKS_MCQ_I2.toString(), List.of("B")))));

    LongitudinalEvidenceFinding finding = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(finding.state()).isEqualTo(LongitudinalEvidenceState.LATER_MIXED_EVIDENCE);
    assertThat(finding.laterEvidence().supportingCount()).isEqualTo(1);
    assertThat(finding.laterEvidence().contradictoryCount()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Exact provenance-set subtraction: baseline evidence is never re-counted as later evidence.
  // -------------------------------------------------------------------------------------------

  @Test
  void baselineEvidenceIdsAreExcludedFromLaterEvidenceIds() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-provenance-subtraction");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-provenance-subtraction");

    UUID baselineAttempt = freshAttempt(learner.id());
    submit(learner.subject(), baselineAttempt, oneResponse(ACKS_MCQ_A1, "A"));
    UUID baselineEvidenceId = onlyEvidenceObservationId(learner.id(), misconceptionId);

    UUID laterAttempt = freshAttempt(learner.id());
    submit(learner.subject(), laterAttempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceFinding finding = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(finding.laterEvidenceObservationIds())
        .doesNotContain(baselineEvidenceId)
        .hasSize(1);
  }

  // -------------------------------------------------------------------------------------------
  // Confidence coverage.
  // -------------------------------------------------------------------------------------------

  @Test
  void coverageIsCurrentImmediatelyAfterARealSubmission() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-coverage-current");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-coverage-current");

    UUID attempt = freshAttempt(learner.id());
    submit(learner.subject(), attempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceFinding finding = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(finding.latestConfidence()).isNotNull();
    assertThat(finding.confidenceCoverage()).isEqualTo(ConfidenceCoverage.CURRENT);
  }

  @Test
  void coverageIsStaleWhenLaterEvidenceHasNoAccompanyingG3Recompute() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-coverage-stale");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-coverage-stale");

    UUID baselineAttempt = freshAttempt(learner.id());
    submit(learner.subject(), baselineAttempt, oneResponse(ACKS_MCQ_A1, "A"));

    // Direct G2 capture only, bypassing G3 recompute entirely -- the latest persisted snapshot's own
    // provenance cannot possibly cite this new evidence.
    UUID laterAttempt = freshAttempt(learner.id());
    insertResponse(laterAttempt, ACKS_MCQ_A1, "A", false);
    captureService.captureEvidence(learner.id(), laterAttempt, ACKS_MCQ_A1);

    LongitudinalEvidenceFinding finding = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    assertThat(finding.confidenceCoverage()).isEqualTo(ConfidenceCoverage.STALE_RELATIVE_TO_LATER_EVIDENCE);
  }

  // -------------------------------------------------------------------------------------------
  // Isolation: different misconception ids, different learners, different domains.
  // -------------------------------------------------------------------------------------------

  @Test
  void differentMisconceptionIdsRemainIsolatedEvenWithSimilarNames() {
    wire();
    UUID misconceptionA = newPublishedMisconceptionTargetingObjective("h7-isolation duplicate name");
    UUID misconceptionB = newPublishedMisconceptionTargetingObjective("h7-isolation duplicate name");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionA);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionB);
    Learner learner = learners.provisionForSubject("h7-isolation-misconceptions");

    UUID attempt = freshAttempt(learner.id());
    submit(learner.subject(), attempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceFinding findingA = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionA.toString()));
    LongitudinalEvidenceFinding findingB = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionB.toString()));

    assertThat(findingA.dataStatus()).isEqualTo(LongitudinalDataStatus.HAS_BASELINE);
    assertThat(findingB.dataStatus()).isEqualTo(LongitudinalDataStatus.NO_BASELINE);
  }

  @Test
  void learnersAreIsolatedFromEachOther() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-isolation-learners");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learnerOne = learners.provisionForSubject("h7-isolation-learner-one");
    Learner learnerTwo = learners.provisionForSubject("h7-isolation-learner-two");

    UUID attempt = freshAttempt(learnerOne.id());
    submit(learnerOne.subject(), attempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceFinding findingOne = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learnerOne.id(), misconceptionId.toString()));
    LongitudinalEvidenceFinding findingTwo = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learnerTwo.id(), misconceptionId.toString()));

    assertThat(findingOne.dataStatus()).isEqualTo(LongitudinalDataStatus.HAS_BASELINE);
    assertThat(findingTwo.dataStatus()).isEqualTo(LongitudinalDataStatus.NO_BASELINE);
  }

  @Test
  void domainSummaryExcludesMisconceptionsInAnotherDomain() {
    wire();
    UUID otherDomainObjectiveId = createObjectiveInFreshDomain("H7ISO");
    UUID inDomainMisconception = newPublishedMisconceptionTargetingObjective("h7-domain-in");
    UUID otherDomainMisconception = UUID.randomUUID();
    misconceptions.insertTargetingObjective(
        otherDomainMisconception, "h7-domain-out", "H7 fixture", otherDomainObjectiveId);
    misconceptions.publish(otherDomainMisconception);
    mapAndPublish(ACKS_MCQ_A1, "A", inDomainMisconception);

    Learner learner = learners.provisionForSubject("h7-domain-summary");
    UUID attempt = freshAttempt(learner.id());
    submit(learner.subject(), attempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceReport summary =
        longitudinalService.domainSummaryForLearner(learner.id(), "KAFKA");
    assertThat(summary.findings())
        .extracting(LongitudinalEvidenceFinding::misconceptionId)
        .contains(inDomainMisconception)
        .doesNotContain(otherDomainMisconception);
  }

  @Test
  void domainSummaryOnlyIncludesHasBaselineFindings() {
    wire();
    UUID evidencedMisconception = newPublishedMisconceptionTargetingObjective("h7-summary-evidenced");
    UUID unevidencedMisconception = newPublishedMisconceptionTargetingObjective("h7-summary-unevidenced");
    mapAndPublish(ACKS_MCQ_A1, "A", evidencedMisconception);
    mapAndPublish(ACKS_MCQ_I2, "A", unevidencedMisconception);
    Learner learner = learners.provisionForSubject("h7-summary-filter");

    UUID attempt = freshAttempt(learner.id());
    submit(learner.subject(), attempt, oneResponse(ACKS_MCQ_A1, "A"));

    LongitudinalEvidenceReport summary =
        longitudinalService.domainSummaryForLearner(learner.id(), "KAFKA");
    assertThat(summary.findings())
        .extracting(LongitudinalEvidenceFinding::misconceptionId)
        .contains(evidencedMisconception)
        .doesNotContain(unevidencedMisconception);
  }

  // -------------------------------------------------------------------------------------------
  // Ordering: deterministic presentation order under a genuine created_at tie -- never causal.
  // -------------------------------------------------------------------------------------------

  @Test
  void laterEvidenceOrderingIsDeterministicUnderATimestampTieButNotCausal() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-ordering-tie");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    mapAndPublish(ACKS_MCQ_I2, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-ordering-tie");

    UUID baselineAttempt = freshAttempt(learner.id());
    submit(learner.subject(), baselineAttempt, oneResponse(ACKS_MCQ_A1, "B")); // CONTRADICTORY baseline

    // Two later evidence rows forced to share the exact same created_at (CURRENT_TIMESTAMP is fixed
    // per transaction in PostgreSQL) -- a genuine tie, not merely a close two.
    transactionTemplate.execute(status -> {
      UUID attemptA = freshAttempt(learner.id());
      insertResponse(attemptA, ACKS_MCQ_A1, "A", false);
      completeAttempt(attemptA);
      captureService.captureEvidence(learner.id(), attemptA, ACKS_MCQ_A1);

      UUID attemptB = freshAttempt(learner.id());
      insertResponse(attemptB, ACKS_MCQ_I2, "A", false);
      completeAttempt(attemptB);
      captureService.captureEvidence(learner.id(), attemptB, ACKS_MCQ_I2);
      return null;
    });

    LongitudinalEvidenceFinding firstRead = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));
    LongitudinalEvidenceFinding secondRead = onlyFindingFor(
        longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString()));

    // Deterministic: repeated reads under a genuine created_at tie always resolve to the exact same
    // order (the id tiebreak), never a different one per call -- but this proves determinism of
    // presentation only, never a claim about which evidence was generated first (M2-ADR-030 §I).
    assertThat(secondRead.laterEvidenceObservationIds())
        .containsExactlyElementsOf(firstRead.laterEvidenceObservationIds());
    assertThat(firstRead.laterEvidenceObservationIds()).hasSize(2);
  }

  // -------------------------------------------------------------------------------------------
  // Boundaries: no mastery, no H5, no writes.
  // -------------------------------------------------------------------------------------------

  @Test
  void longitudinalServiceNeverWritesAnyRow() {
    wire();
    UUID misconceptionId = newPublishedMisconceptionTargetingObjective("h7-no-writes");
    mapAndPublish(ACKS_MCQ_A1, "A", misconceptionId);
    Learner learner = learners.provisionForSubject("h7-no-writes");
    UUID attempt = freshAttempt(learner.id());
    submit(learner.subject(), attempt, oneResponse(ACKS_MCQ_A1, "A"));

    long evidenceCountBefore = countRows("core.misconception_evidence_observation");
    long confidenceCountBefore = countRows("core.misconception_confidence_observation");
    long masteryCountBefore = countRows("ledger.mastery_snapshot");

    longitudinalService.misconceptionDetailForLearner(learner.id(), misconceptionId.toString());
    longitudinalService.domainSummaryForLearner(learner.id(), "KAFKA");

    assertThat(countRows("core.misconception_evidence_observation")).isEqualTo(evidenceCountBefore);
    assertThat(countRows("core.misconception_confidence_observation")).isEqualTo(confidenceCountBefore);
    assertThat(countRows("ledger.mastery_snapshot")).isEqualTo(masteryCountBefore);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID newPublishedMisconceptionTargetingObjective(String name) {
    UUID id = UUID.randomUUID();
    misconceptions.insertTargetingObjective(id, name, "H7 fixture", ACKS_DURABILITY_TRADEOFFS);
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
        """, attemptId, learnerId, ASSESSMENT_V2, "h7-fixture-" + attemptId);
    return attemptId;
  }

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

  private LongitudinalEvidenceFinding onlyFindingFor(LongitudinalEvidenceReport report) {
    assertThat(report.findings()).hasSize(1);
    return report.findings().get(0);
  }

  private UUID onlyEvidenceObservationId(UUID learnerId, UUID misconceptionId) {
    List<UUID> ids = runtimeJdbc.query("""
        SELECT id FROM core.misconception_evidence_observation
        WHERE learner_id = ? AND misconception_id = ?
        """, (result, row) -> result.getObject("id", UUID.class), learnerId, misconceptionId);
    assertThat(ids).hasSize(1);
    return ids.get(0);
  }

  private long countRows(String table) {
    Long count = runtimeJdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return count == null ? 0 : count;
  }

  /** A fresh domain/curriculum/skill/objective with no relation to KAFKA -- used only to prove domain
   * filtering is real, not merely assumed. Duplicated from {@code
   * DiagnosticReportPersistenceIntegrationTests}'s own helper on purpose (H7's own H6-untouched
   * boundary extends to test infrastructure too). */
  private UUID createObjectiveInFreshDomain(String domainCode) {
    UUID domainId = UUID.randomUUID();
    runtimeJdbc.update(
        "INSERT INTO core.learning_domain (id, code, name) VALUES (?, ?, ?)",
        domainId, domainCode, domainCode + " domain");

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

    runtimeJdbc.update(
        "UPDATE core.curriculum_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = ?",
        curriculumVersionId);

    return objectiveId;
  }

  private SubmissionResult submit(String subject, UUID attemptId, DiagnosticSubmissionRequest request) {
    MDC.put("interactionId", "01920000-0000-7000-8000-0000000000f4");
    try {
      return transactionTemplate.execute(
          status -> submissions.submit(subject, "KAFKA", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }
  }

  private void wire() {
    if (longitudinalService == null) {
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

      LongitudinalEvidenceRepository longitudinalRepository = new LongitudinalEvidenceRepository(runtimeJdbc);
      longitudinalService = new LongitudinalEvidenceService(
          assessments, learnerService, longitudinalRepository, confidenceRepository,
          new LongitudinalEvidencePolicyV1());
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
