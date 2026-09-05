package io.ramals.learningplatform.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.AdaptiveDiagnosticFormProperties;
import io.ramals.learningplatform.assessment.AdaptiveDiagnosticSelector;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceRepository;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceService;
import io.ramals.learningplatform.assessment.DiagnosticFormProperties;
import io.ramals.learningplatform.assessment.DiagnosticFormSelector;
import io.ramals.learningplatform.assessment.AssessmentRepository;
import io.ramals.learningplatform.assessment.AttemptCreation;
import io.ramals.learningplatform.assessment.DiagnosticItem;
import io.ramals.learningplatform.assessment.DiagnosticScorerV2;
import io.ramals.learningplatform.assessment.DiagnosticService;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionService;
import io.ramals.learningplatform.assessment.MisconceptionEvidenceCaptureService;
import io.ramals.learningplatform.assessment.MisconceptionEvidenceObservationRepository;
import io.ramals.learningplatform.assessment.MisconceptionOptionMappingRepository;
import io.ramals.learningplatform.assessment.ProbeProvenanceRepository;
import io.ramals.learningplatform.assessment.ProbeRelationshipRepository;
import io.ramals.learningplatform.assessment.ProbeRelationshipService;
import io.ramals.learningplatform.assessment.SubmissionResult;
import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.learning.LearningSession;
import io.ramals.learningplatform.learning.LearningSessionCommand;
import io.ramals.learningplatform.learning.LearningSessionPolicy;
import io.ramals.learningplatform.learning.LearningSessionRepository;
import io.ramals.learningplatform.learning.LearningSessionService;
import io.ramals.learningplatform.learning.ProgressionPolicy;
import io.ramals.learningplatform.learning.ProgressionRepository;
import io.ramals.learningplatform.learning.ProgressionService;
import io.ramals.learningplatform.learning.ProgressionState;
import io.ramals.learningplatform.learning.SessionTransitionRequest;
import io.ramals.learningplatform.learning.SkillProgression;
import io.ramals.learningplatform.mastery.EvidenceConfidenceCalculatorV2;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.mastery.MasteryStatusPolicyV2;
import io.ramals.learningplatform.mastery.WeightedMasteryCalculator;
import io.ramals.learningplatform.recommendation.DecisionRecord;
import io.ramals.learningplatform.recommendation.LearningRecommendation;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-0 end-to-end validation. Exercises the complete deterministic vertical slice against real
 * PostgreSQL and runs the release drills: safe retry, service restart/recovery, historical decision
 * reconstruction, and forced database failure discoverable by interactionId alone.
 *
 * <p>This is the executable form of the MVP-0 Definition of Done; the archived evidence lives in
 * docs/validation/mvp0-validation-report.md.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
@TestMethodOrder(OrderAnnotation.class)
class MvpZeroValidationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID CURRICULUM_VERSION = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final String SUBJECT = "e2e-learner";
  private static final String INTERACTION_ID = "01920000-0000-7000-8000-00000000e2e0";

  private static String databaseUrl;
  private static UUID learnerId;
  private static UUID attemptId;
  private static UUID decisionRecordId;

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private LearnerRepository learners;
  private EvidenceRepository evidence;
  private MasteryRepository masteryRepository;
  private RecommendationRepository recommendations;
  private DiagnosticService diagnostics;
  private DiagnosticSubmissionService submissions;
  private MasteryService masteryService;
  private ProgressionService progression;
  private LearningSessionService sessions;

  @BeforeAll
  static void freshInstall() throws SQLException {
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

    // Fresh install: migrate an empty database from V001 to head.
    var result = org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
    assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(13);
  }

  /** Builds a complete, independently wired application graph — a "process start". */
  private void start() {
    dataSource = new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
    jdbc = new JdbcTemplate(dataSource);
    tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    JsonMapper mapper = JsonMapper.builder().build();

    learners = new LearnerRepository(jdbc);
    evidence = new EvidenceRepository(jdbc);
    masteryRepository = new MasteryRepository(jdbc);
    recommendations = new RecommendationRepository(jdbc);
    AssessmentRepository assessments = new AssessmentRepository(jdbc, mapper);
    LearnerService learnerService = new LearnerService(learners);
    CurriculumService curriculumService =
        new CurriculumService(new CurriculumRepository(jdbc), new CurriculumGraphValidator());

    masteryService = new MasteryService(masteryRepository, evidence, new WeightedMasteryCalculator(),
        new EvidenceConfidenceCalculatorV2(), new MasteryStatusPolicyV2());
    RecommendationService recommendationService =
        new RecommendationService(new RecommendationPolicy(), recommendations, learnerService);
    diagnostics = new DiagnosticService(assessments, learnerService, formSelector(),
        new AdaptiveDiagnosticSelector(new AdaptiveDiagnosticFormProperties()), masteryRepository,
        curriculumService,
        new ProbeRelationshipService(new ProbeRelationshipRepository(jdbc), assessments),
        new ProbeProvenanceRepository(jdbc));
    submissions = new DiagnosticSubmissionService(assessments, learnerService, new DiagnosticScorerV2(),
        new EvidenceService(evidence), masteryService, recommendationService,
        new DiagnosticConfidenceService(new ProbeProvenanceRepository(jdbc),
            new DiagnosticConfidenceRepository(jdbc), new DiagnosticConfidenceCalculatorV1()),
        new MisconceptionEvidenceCaptureService(new MisconceptionOptionMappingRepository(jdbc),
            new MisconceptionEvidenceObservationRepository(jdbc)),
        mapper);
    progression = new ProgressionService(curriculumService, learnerService,
        new ProgressionRepository(jdbc), new ProgressionPolicy());
    sessions = new LearningSessionService(new LearningSessionRepository(jdbc, mapper),
        new LearningSessionPolicy(), learnerService, curriculumService);
  }

  private <T> T inInteraction(String interactionId, java.util.function.Supplier<T> action) {
    MDC.put("interactionId", interactionId);
    MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    try {
      return action.get();
    } finally {
      MDC.clear();
    }
  }

  @Test
  @Order(1)
  void fullKafkaDiagnosticVerticalSlice() {
    start();

    // 1. A learner starts a durable learning session.
    LearningSession session = inInteraction(INTERACTION_ID,
        () -> sessions.start(SUBJECT, "KAFKA", "v1").session());
    assertThat(session.status().name()).isEqualTo("ACTIVE");
    learnerId = learners.findBySubject(SUBJECT).orElseThrow().id();

    // 2. Progression before any evidence: the foundational skill is available, dependents locked.
    List<SkillProgression> before = progression.progression(SUBJECT, "KAFKA", "v1");
    assertThat(state(before, "KAFKA_BROKER")).isEqualTo(ProgressionState.ELIGIBLE);
    assertThat(state(before, "KAFKA_TOPIC")).isEqualTo(ProgressionState.LOCKED);

    // 3. Create and complete the curated diagnostic.
    AttemptCreation creation = inInteraction(INTERACTION_ID,
        () -> diagnostics.createAttempt(SUBJECT, "kafka", "e2e-key-1"));
    assertThat(creation.created()).isTrue();
    attemptId = creation.attempt().id();

    List<DiagnosticItem> items = diagnostics
        .getAttempt(SUBJECT, "kafka", attemptId.toString()).items();
    assertThat(items).isNotEmpty();
    // Answer every item correctly using the seeded curated answers.
    DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(items.stream()
        .map(item -> new ItemResponse(item.id().toString(), List.of(correctOption(item))))
        .toList());

    SubmissionResult result = inInteraction(INTERACTION_ID, () ->
        tx.execute(status -> submissions.submit(SUBJECT, "kafka", attemptId.toString(), request)));
    assertThat(result.attempt().status()).isEqualTo("COMPLETED");
    assertThat(result.skillScores()).isNotEmpty();

    // 4. Immutable evidence was appended with interactionId provenance.
    List<Evidence> brokerEvidence = evidence.findByLearnerAndSkill(learnerId, BROKER_SKILL);
    assertThat(brokerEvidence).hasSize(1);
    assertThat(brokerEvidence.getFirst().interactionId()).isEqualTo(INTERACTION_ID);

    // 5. A deterministic mastery snapshot with evidence confidence was computed.
    MasterySnapshot snapshot = masteryService
        .latestSnapshot(learnerId, BROKER_SKILL, CURRICULUM_VERSION).orElseThrow();
    assertThat(snapshot.aggregateVersion()).isEqualTo(1);
    assertThat(snapshot.masteryScore()).isEqualByComparingTo("1.0000");
    assertThat(snapshot.evidenceConfidence()).isNotNull();
    assertThat(snapshot.algorithmVersion()).isEqualTo(WeightedMasteryCalculator.ALGORITHM_VERSION);

    // 6. A recommendation and its immutable decision record were produced.
    LearningRecommendation recommendation = recommendations.findCurrentByLearner(learnerId).stream()
        .filter(item -> item.skillId().equals(BROKER_SKILL)).findFirst().orElseThrow();
    decisionRecordId = recommendation.decisionRecordId();
    assertThat(recommendations.findDecisionById(decisionRecordId)).isPresent();

    // 7. The session completes.
    LearningSession completed = inInteraction(INTERACTION_ID, () -> sessions.transition(
        SUBJECT, session.id().toString(),
        new SessionTransitionRequest(LearningSessionCommand.COMPLETE, session.version(), null)));
    assertThat(completed.status().name()).isEqualTo("COMPLETED");
  }

  @Test
  @Order(2)
  void safeRetryDrillCreatesNoDuplicateState() {
    start();
    long evidenceBefore = count("SELECT count(*) FROM ledger.evidence WHERE learner_id = ?", learnerId);
    long snapshotsBefore = count(
        "SELECT count(*) FROM ledger.mastery_snapshot WHERE learner_id = ?", learnerId);
    long decisionsBefore = count(
        "SELECT count(*) FROM ledger.decision_record WHERE learner_id = ?", learnerId);

    // Retrying attempt creation with the original key returns the same logical attempt.
    AttemptCreation replay = inInteraction(INTERACTION_ID,
        () -> diagnostics.createAttempt(SUBJECT, "kafka", "e2e-key-1"));
    assertThat(replay.created()).isFalse();
    assertThat(replay.attempt().id()).isEqualTo(attemptId);

    // Re-submitting the completed attempt returns the original result and writes nothing.
    DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(UUID.randomUUID().toString(), List.of("A"))));
    SubmissionResult replayed = inInteraction(INTERACTION_ID, () ->
        tx.execute(status -> submissions.submit(SUBJECT, "kafka", attemptId.toString(), request)));
    assertThat(replayed.attempt().status()).isEqualTo("COMPLETED");

    assertThat(count("SELECT count(*) FROM ledger.evidence WHERE learner_id = ?", learnerId))
        .isEqualTo(evidenceBefore);
    assertThat(count("SELECT count(*) FROM ledger.mastery_snapshot WHERE learner_id = ?", learnerId))
        .isEqualTo(snapshotsBefore);
    assertThat(count("SELECT count(*) FROM ledger.decision_record WHERE learner_id = ?", learnerId))
        .isEqualTo(decisionsBefore);
  }

  @Test
  @Order(3)
  void serviceRestartPreservesAuthoritativeState() {
    // A completely fresh object graph stands in for a process restart: nothing is cached in memory,
    // so everything below is read back from durable state.
    start();

    assertThat(learners.findBySubject(SUBJECT)).isPresent();
    assertThat(masteryService.latestSnapshot(learnerId, BROKER_SKILL, CURRICULUM_VERSION))
        .isPresent();
    assertThat(recommendations.findCurrentByLearner(learnerId)).isNotEmpty();
    assertThat(progression.progression(SUBJECT, "KAFKA", "v1")).isNotEmpty();

    // A new session after the previous one completed starts cleanly rather than resuming a stale one.
    assertThat(inInteraction(INTERACTION_ID, () -> sessions.start(SUBJECT, "KAFKA", "v1")).created())
        .isTrue();
  }

  @Test
  @Order(4)
  void historicalDecisionIsReconstructableFromItsRecord() {
    start();
    DecisionRecord decision = recommendations.findDecisionById(decisionRecordId).orElseThrow();
    MasterySnapshot snapshot = masteryRepository.findById(decision.sourceSnapshotId()).orElseThrow();

    // The decision is self-describing: it cites the exact snapshot, inputs, and every version.
    assertThat(decision.masteryScore()).isEqualByComparingTo(snapshot.masteryScore());
    assertThat(decision.evidenceConfidence()).isEqualByComparingTo(snapshot.evidenceConfidence());
    assertThat(decision.masteryAlgorithmVersion()).isEqualTo(snapshot.algorithmVersion());
    assertThat(decision.policyVersion()).isEqualTo(RecommendationPolicy.POLICY_VERSION);
    assertThat(decision.interactionId()).isEqualTo(INTERACTION_ID);

    // Replaying the recorded policy against the cited snapshot reproduces the recorded action.
    assertThat(new RecommendationPolicy().decide(snapshot).action())
        .isEqualTo(decision.recommendedAction());

    // The evidence behind the snapshot is still present and immutable.
    assertThat(evidence.findByLearnerAndSkill(learnerId, BROKER_SKILL)).isNotEmpty();
  }

  @Test
  @Order(5)
  void failureTraceDrillFindsEverythingByInteractionIdAlone() {
    start();
    // Support receives only the interactionId from the learner's error banner.
    assertThat(recommendations.findDecisionsByInteractionId(INTERACTION_ID))
        .isNotEmpty()
        .allSatisfy(decision -> assertThat(decision.interactionId()).isEqualTo(INTERACTION_ID));

    Long evidenceByInteraction = jdbc.queryForObject(
        "SELECT count(*) FROM ledger.evidence WHERE interaction_id = ?", Long.class, INTERACTION_ID);
    assertThat(evidenceByInteraction).isPositive();

    Long snapshotsByInteraction = jdbc.queryForObject(
        "SELECT count(*) FROM ledger.mastery_snapshot WHERE interaction_id = ?",
        Long.class, INTERACTION_ID);
    assertThat(snapshotsByInteraction).isPositive();

    Long transitionsByInteraction = jdbc.queryForObject(
        "SELECT count(*) FROM core.learning_session_transition WHERE interaction_id = ?",
        Long.class, INTERACTION_ID);
    assertThat(transitionsByInteraction).isPositive();
  }

  @Test
  @Order(6)
  void forcedDatabaseFailureIsContainedAndImmutabilityHolds() {
    start();
    // Forced failure: the least-privileged runtime identity cannot rewrite provenance.
    UUID evidenceId = evidence.findByLearnerAndSkill(learnerId, BROKER_SKILL).getFirst().id();
    assertThatThrownBy(() -> jdbc.update(
        "UPDATE ledger.evidence SET normalized_score = 0 WHERE id = ?", evidenceId))
        .isInstanceOfSatisfying(DataAccessException.class,
            failure -> assertThat(sqlState(failure)).isEqualTo("42501"));

    // Forced failure: a nonexistent relation surfaces as a contained DataAccessException rather
    // than corrupting state, and the learner's authoritative data is untouched.
    assertThatThrownBy(() -> jdbc.queryForObject("SELECT 1 FROM core.does_not_exist", Integer.class))
        .isInstanceOf(DataAccessException.class);
    assertThat(masteryService.latestSnapshot(learnerId, BROKER_SKILL, CURRICULUM_VERSION))
        .isPresent();
  }

  private static String correctOption(DiagnosticItem item) {
    // The curated seed's correct answers, keyed by item code.
    return switch (item.itemCode()) {
      case "KAFKA_DIAG_BROKER" -> "B";
      case "KAFKA_DIAG_TOPIC" -> "C";
      case "KAFKA_DIAG_PARTITION" -> "B";
      case "KAFKA_DIAG_ACKS" -> "C";
      case "KAFKA_DIAG_CONSUMER_GROUPS" -> "B";
      default -> item.options().getFirst().id();
    };
  }

  private static ProgressionState state(List<SkillProgression> all, String skillCode) {
    return all.stream().filter(skill -> skill.skillCode().equals(skillCode))
        .findFirst().orElseThrow().state();
  }

  private long count(String sql, Object argument) {
    return jdbc.queryForObject(sql, Long.class, argument);
  }

  private static String sqlState(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for validation");
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

  /**
   * The production selector on its default settings, so these fixtures assemble forms exactly the
   * way a deployment does rather than through a stand-in that could drift from it.
   */
  private static DiagnosticFormSelector formSelector() {
    return new DiagnosticFormSelector(new DiagnosticFormProperties());
  }
}
