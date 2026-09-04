package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * H4b foundation (M2-ADR-024) against real PostgreSQL and the real, already-seeded KAFKA v2
 * curriculum ({@code curriculum_version} '...0004', H3) and assessment bank (V049) -- no invented
 * content anywhere in this class. Every objective id and item count used below was verified against
 * a real migrated database before V054 was authored; see V054's own header comment.
 *
 * <p>This class reads only -- it never calls {@code DiagnosticService} or
 * {@code DiagnosticSubmissionService}, and inserts test fixtures (attempts, responses, exposure)
 * with raw SQL exactly where those services would normally own the write, the same way earlier
 * fixture classes in this suite do for state their own service under test does not write.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ProbeRelationshipServicePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");

  // Real objective ids, v2 curriculum ('...0004') -- verified against a real migrated database.
  private static final UUID ACKS_SEMANTICS = UUID.fromString("01900000-0000-7000-8000-000000000d10");
  private static final UUID ACKS_DURABILITY_TRADEOFFS =
      UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID PRODUCER_IDEMPOTENCE =
      UUID.fromString("01900000-0000-7000-8000-000000000d12");
  private static final UUID BROKER_STORAGE_MODEL =
      UUID.fromString("01900000-0000-7000-8000-000000000d01");

  // Real item ids (all VERIFIED_CONTENT, from V049), tagged as above.
  private static final UUID ACKS_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000622"); // d10
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624"); // d11
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625"); // d11
  private static final UUID ACKS_MCQ_A2 = UUID.fromString("01900000-0000-7000-8000-000000000626"); // d12, only item
  private static final UUID TOPIC_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000610"); // TOPIC/d05
  private static final UUID BROKER_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000601"); // d01
  private static final UUID BROKER_FILL_F = UUID.fromString("01900000-0000-7000-8000-000000000606"); // d01

  private static String databaseUrl;
  private ProbeRelationshipRepository repository;
  private ProbeRelationshipService service;
  private LearnerRepository learners;
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
  }

  // -------------------------------------------------------------------------------------------
  // Refinement 1, case A: a real, published ROOT_CAUSE_PROBE resolves to a real, unseen candidate.
  // -------------------------------------------------------------------------------------------

  @Test
  void rootCauseProbeWithRealContentOnBothEndsResolvesToACandidatesAvailableCandidate() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-root-cause-available");

    ProbeResolution resolution =
        service.resolve(ACKS_MCQ_A1, ProbeRelationshipType.ROOT_CAUSE_PROBE, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.CANDIDATES_AVAILABLE);
    assertThat(resolution.hypothesis().triggerItemVersionId()).isEqualTo(ACKS_MCQ_A1);
    assertThat(resolution.hypothesis().triggerObjectiveId()).isEqualTo(ACKS_DURABILITY_TRADEOFFS);
    assertThat(resolution.hypothesis().targetObjectiveId()).isEqualTo(PRODUCER_IDEMPOTENCE);
    assertThat(resolution.hypothesis().authorizingRelationshipId())
        .isEqualTo(UUID.fromString("01900000-0000-7000-8000-000000000e01"));
    assertThat(resolution.candidates()).extracting(ProbeCandidateItem::itemVersionId)
        .containsExactly(ACKS_MCQ_A2);
  }

  // -------------------------------------------------------------------------------------------
  // Refinement 1, case B: a real, published, valid relationship whose target objective has no
  // real content -- an explicit, distinct outcome, never a fallback and never invented content.
  // -------------------------------------------------------------------------------------------

  @Test
  void rootCauseProbeWhoseTargetHasNoRealContentIsReportedAsDefinedButNoItems() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-root-cause-no-items");

    ProbeResolution resolution =
        service.resolve(ACKS_MCQ_A2, ProbeRelationshipType.ROOT_CAUSE_PROBE, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.RELATIONSHIP_DEFINED_BUT_NO_ITEMS);
    assertThat(resolution.hypothesis()).isNotNull();
    assertThat(resolution.hypothesis().targetObjectiveId())
        .isEqualTo(UUID.fromString("01900000-0000-7000-8000-000000000c08")); // IDEMPOTENT_DELIVERY
    assertThat(resolution.candidates()).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // CONTRADICTION_CHECK, real content on both ends.
  // -------------------------------------------------------------------------------------------

  @Test
  void contradictionCheckWithRealContentResolvesToCandidatesAvailable() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-contradiction-check");

    ProbeResolution resolution =
        service.resolve(ACKS_MCQ_I2, ProbeRelationshipType.CONTRADICTION_CHECK, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.CANDIDATES_AVAILABLE);
    assertThat(resolution.hypothesis().targetObjectiveId()).isEqualTo(ACKS_SEMANTICS);
    assertThat(resolution.candidates()).isNotEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // PREREQUISITE_VALIDATION: the real KAFKA_TOPIC -> KAFKA_BROKER curriculum edge, never stored
  // as a diagnostic_probe_relationship row -- read straight from core.skill_prerequisite.
  // -------------------------------------------------------------------------------------------

  @Test
  void prerequisiteValidationResolvesFromTheRealCurriculumGraphNotANewRow() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-prerequisite-validation");

    ProbeResolution resolution =
        service.resolve(TOPIC_MCQ_I2, ProbeRelationshipType.PREREQUISITE_VALIDATION, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.CANDIDATES_AVAILABLE);
    assertThat(resolution.hypothesis().targetObjectiveId()).isEqualTo(BROKER_STORAGE_MODEL);
    // Read from skill_prerequisite/learning_objective, not diagnostic_probe_relationship.
    assertThat(resolution.hypothesis().authorizingRelationshipId()).isNull();
    assertThat(resolution.candidates()).extracting(ProbeCandidateItem::itemVersionId)
        .containsExactly(BROKER_MCQ_F, BROKER_FILL_F);
  }

  // -------------------------------------------------------------------------------------------
  // SAME_OBJECTIVE_CONFIRMATION, real content.
  // -------------------------------------------------------------------------------------------

  @Test
  void sameObjectiveConfirmationExcludesOnlyTheTriggerItemItself() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-same-objective");

    ProbeResolution resolution =
        service.resolve(ACKS_MCQ_F, ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.CANDIDATES_AVAILABLE);
    assertThat(resolution.hypothesis().targetObjectiveId()).isEqualTo(ACKS_SEMANTICS);
    assertThat(resolution.candidates()).extracting(ProbeCandidateItem::itemVersionId)
        .doesNotContain(ACKS_MCQ_F);
  }

  // -------------------------------------------------------------------------------------------
  // Exposure: a real prior attempt that presented the only PRODUCER_IDEMPOTENCE item makes the
  // otherwise-available case A relationship report ALL_CANDIDATES_ALREADY_EXPOSED instead.
  // -------------------------------------------------------------------------------------------

  @Test
  void aPreviouslyExposedOnlyCandidateIsReportedAsAllCandidatesAlreadyExposed() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-exposed-only-candidate");
    exposeItem(learner.id(), ACKS_MCQ_A2);

    ProbeResolution resolution =
        service.resolve(ACKS_MCQ_A1, ProbeRelationshipType.ROOT_CAUSE_PROBE, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.ALL_CANDIDATES_ALREADY_EXPOSED);
    assertThat(resolution.hypothesis()).isNotNull();
    assertThat(resolution.candidates()).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Evidence classification against a real assessment_response row.
  // -------------------------------------------------------------------------------------------

  @Test
  void anIncorrectRealProbeResponseIsSupportingEvidence() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-evidence-supporting");
    UUID attemptId = insertInProgressAttempt(learner.id());
    insertResponse(attemptId, ACKS_MCQ_A2, false);
    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        PRODUCER_IDEMPOTENCE, UUID.fromString("01900000-0000-7000-8000-000000000e01"));

    Optional<HypothesisEvidence> evidence = service.evidenceFor(hypothesis, attemptId, ACKS_MCQ_A2);

    assertThat(evidence).isPresent();
    assertThat(evidence.get().isCorrect()).isFalse();
    assertThat(evidence.get().outcome()).isEqualTo(HypothesisEvidenceOutcome.SUPPORTING);
  }

  @Test
  void aCorrectRealProbeResponseIsContradictoryEvidence() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-evidence-contradictory");
    UUID attemptId = insertInProgressAttempt(learner.id());
    insertResponse(attemptId, ACKS_MCQ_A2, true);
    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        PRODUCER_IDEMPOTENCE, UUID.fromString("01900000-0000-7000-8000-000000000e01"));

    Optional<HypothesisEvidence> evidence = service.evidenceFor(hypothesis, attemptId, ACKS_MCQ_A2);

    assertThat(evidence).isPresent();
    assertThat(evidence.get().isCorrect()).isTrue();
    assertThat(evidence.get().outcome()).isEqualTo(HypothesisEvidenceOutcome.CONTRADICTORY);
  }

  // -------------------------------------------------------------------------------------------
  // No mutation of mastery state: resolving hypotheses and reading evidence, however many times,
  // never writes ledger.mastery_snapshot -- the real-database counterpart to
  // ArchitectureGuardrailTests.probeRelationshipResolutionCannotMutateLearnerState.
  // -------------------------------------------------------------------------------------------

  @Test
  void resolvingHypothesesAndReadingEvidenceNeverWritesAMasterySnapshot() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-no-mastery-mutation");
    UUID attemptId = insertInProgressAttempt(learner.id());
    insertResponse(attemptId, ACKS_MCQ_A2, false);

    service.resolve(ACKS_MCQ_A1, ProbeRelationshipType.ROOT_CAUSE_PROBE, learner.id());
    service.resolve(ACKS_MCQ_F, ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION, learner.id());
    service.resolve(TOPIC_MCQ_I2, ProbeRelationshipType.PREREQUISITE_VALIDATION, learner.id());
    service.evidenceFor(
        new DiagnosticHypothesis(ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS,
            ProbeRelationshipType.ROOT_CAUSE_PROBE, PRODUCER_IDEMPOTENCE,
            UUID.fromString("01900000-0000-7000-8000-000000000e01")),
        attemptId, ACKS_MCQ_A2);

    Integer snapshotCount = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM ledger.mastery_snapshot WHERE learner_id = ?", Integer.class,
        learner.id());
    assertThat(snapshotCount).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private void exposeItem(UUID learnerId, UUID itemVersionId) {
    UUID attemptId = insertInProgressAttempt(learnerId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
  }

  private UUID insertInProgressAttempt(UUID learnerId) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "h4b-fixture-" + attemptId);
    return attemptId;
  }

  private void insertResponse(UUID attemptId, UUID itemVersionId, boolean isCorrect) {
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"selected":["A"]}'::jsonb, ?)
        """, UUID.randomUUID(), attemptId, itemVersionId, isCorrect);
  }

  private void wire() {
    if (service == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      learners = new LearnerRepository(runtimeJdbc);
      repository = new ProbeRelationshipRepository(runtimeJdbc);
      AssessmentRepository assessmentRepository =
          new AssessmentRepository(runtimeJdbc, JsonMapper.builder().build());
      service = new ProbeRelationshipService(repository, assessmentRepository);
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
