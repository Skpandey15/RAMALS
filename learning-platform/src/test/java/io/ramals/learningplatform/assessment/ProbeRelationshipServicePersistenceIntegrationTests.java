package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  private static final UUID BROKER_CONTROLLER_ROLE =
      UUID.fromString("01900000-0000-7000-8000-000000000d02");
  private static final UUID BROKER_CLUSTER_OPERATIONS =
      UUID.fromString("01900000-0000-7000-8000-000000000d03");
  // ISR_DURABILITY (KAFKA_ISR's own carried-forward objective) and REPLICATION_FACTOR
  // (KAFKA_REPLICATION's) -- the real, single-required-objective prerequisite edge used for the
  // unambiguous PREREQUISITE_VALIDATION happy path, since every one of the five skills with real
  // assessment content has three required objectives, not one.
  private static final UUID ISR_DURABILITY = UUID.fromString("01900000-0000-7000-8000-000000000c14");
  private static final UUID REPLICATION_FACTOR = UUID.fromString("01900000-0000-7000-8000-000000000c13");

  // Real item ids (all VERIFIED_CONTENT, from V049), tagged as above.
  private static final UUID ACKS_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000622"); // d10
  private static final UUID ACKS_MCQ_I1 = UUID.fromString("01900000-0000-7000-8000-000000000623"); // d10
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624"); // d11
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625"); // d11
  private static final UUID ACKS_MCQ_A2 = UUID.fromString("01900000-0000-7000-8000-000000000626"); // d12, only item
  private static final UUID TOPIC_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000610"); // TOPIC/d05
  private static final UUID BROKER_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000601"); // d01

  // Test-only fixture ids -- not part of V054's real seed. Documented at each insertion site below.
  private static final UUID ISR_TEST_ITEM =
      UUID.fromString("01900000-0000-7000-8000-000000000f01");
  private static final UUID AMBIGUOUS_ROOT_CAUSE_RELATIONSHIP_1 =
      UUID.fromString("01900000-0000-7000-8000-000000000f02");
  private static final UUID AMBIGUOUS_ROOT_CAUSE_RELATIONSHIP_2 =
      UUID.fromString("01900000-0000-7000-8000-000000000f03");
  private static final UUID GROUP_PARTITION_ASSIGNMENT =
      UUID.fromString("01900000-0000-7000-8000-000000000d13");

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

    // Test-only fixtures, not part of V054's real seed -- added once here, after migration, purely
    // to exercise ambiguity paths the real seed data does not otherwise reach. Each is documented at
    // its own test method; none alters or removes anything V054 itself inserted.
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      // A trigger item for KAFKA_ISR, which (like every one of the ten skills with no real
      // assessment content) has no real item of its own. Needed for the unambiguous
      // PREREQUISITE_VALIDATION happy path: KAFKA_ISR's only curriculum prerequisite,
      // KAFKA_REPLICATION, is one of the ten unsplit skills with exactly one required objective --
      // unlike any of the five skills with real content, which all have three.
      statement.execute("""
          INSERT INTO core.assessment_item_version
            (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
             answer_key_jsonb, difficulty, display_order, trust_state, verified_by, verified_at)
          VALUES ('01900000-0000-7000-8000-000000000f01', '01900000-0000-7000-8000-000000000403',
                  '01900000-0000-7000-8000-000000000114', 'H4B_TEST_ISR_ITEM', 'SINGLE_CHOICE',
                  'Test-only probe stem.',
                  '[{"id":"A","text":"a"},{"id":"B","text":"b"}]'::jsonb, '{"correct":["A"]}'::jsonb,
                  'FOUNDATIONAL', 99, 'VERIFIED_CONTENT', 'h4b-test-fixture', CURRENT_TIMESTAMP)
          """);
      statement.execute("""
          INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id)
          VALUES ('01900000-0000-7000-8000-000000000f01', gen_random_uuid())
          """);
      statement.execute("""
          INSERT INTO core.assessment_item_objective (item_version_id, objective_id)
          VALUES ('01900000-0000-7000-8000-000000000f01', '01900000-0000-7000-8000-000000000c14')
          """);

      // A second test-only item, tagged to REPLICATION_FACTOR (c13, KAFKA_REPLICATION's own
      // carried-forward objective) -- like every one of the ten unsplit objectives, it has zero real
      // items in V049's bank, so without this the "unambiguous" prerequisite test could only reach
      // RELATIONSHIP_DEFINED_BUT_NO_ITEMS, not prove CANDIDATES_AVAILABLE stays reachable for
      // PREREQUISITE_VALIDATION once a target is unambiguous.
      statement.execute("""
          INSERT INTO core.assessment_item_version
            (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
             answer_key_jsonb, difficulty, display_order, trust_state, verified_by, verified_at)
          VALUES ('01900000-0000-7000-8000-000000000f04', '01900000-0000-7000-8000-000000000403',
                  '01900000-0000-7000-8000-000000000113', 'H4B_TEST_REPLICATION_ITEM', 'SINGLE_CHOICE',
                  'Test-only probe stem.',
                  '[{"id":"A","text":"a"},{"id":"B","text":"b"}]'::jsonb, '{"correct":["A"]}'::jsonb,
                  'FOUNDATIONAL', 98, 'VERIFIED_CONTENT', 'h4b-test-fixture', CURRENT_TIMESTAMP)
          """);
      statement.execute("""
          INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id)
          VALUES ('01900000-0000-7000-8000-000000000f04', gen_random_uuid())
          """);
      statement.execute("""
          INSERT INTO core.assessment_item_objective (item_version_id, objective_id)
          VALUES ('01900000-0000-7000-8000-000000000f04', '01900000-0000-7000-8000-000000000c13')
          """);

      // A second objective tag on a real item that is never used as a trigger anywhere else in this
      // class -- BROKER_MCQ_F is only ever an expected *candidate* elsewhere, so tagging it to a
      // second objective here cannot change any other test's outcome. This is the trigger-item
      // ambiguity case: an item tagged to more than one objective has no single objective a
      // hypothesis can be raised from.
      statement.execute("""
          INSERT INTO core.assessment_item_objective (item_version_id, objective_id)
          VALUES ('01900000-0000-7000-8000-000000000601', '01900000-0000-7000-8000-000000000d02')
          """);

      // A second published ROOT_CAUSE_PROBE relationship from ACKS_SEMANTICS (d10) -- otherwise
      // unused as a relationship *source* anywhere in V054's real seed or elsewhere in this class --
      // so that two published rows genuinely exist from the same source objective, the shape the
      // schema's own uniqueness constraint (source, target, type) explicitly permits.
      statement.execute("""
          INSERT INTO core.diagnostic_probe_relationship
            (id, source_objective_id, target_objective_id, relationship_type, status, rationale, published_at)
          VALUES
            ('01900000-0000-7000-8000-000000000f02',
             '01900000-0000-7000-8000-000000000d10', '01900000-0000-7000-8000-000000000d12',
             'ROOT_CAUSE_PROBE', 'PUBLISHED', 'Test-only: exercises AMBIGUOUS_TARGET_OBJECTIVE.',
             CURRENT_TIMESTAMP),
            ('01900000-0000-7000-8000-000000000f03',
             '01900000-0000-7000-8000-000000000d10', '01900000-0000-7000-8000-000000000d13',
             'ROOT_CAUSE_PROBE', 'PUBLISHED', 'Test-only: exercises AMBIGUOUS_TARGET_OBJECTIVE.',
             CURRENT_TIMESTAMP)
          """);
    }
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
  // PREREQUISITE_VALIDATION reads from the real curriculum graph (core.skill_prerequisite /
  // core.learning_objective), never a diagnostic_probe_relationship row -- proven by two real
  // shapes: an unambiguous single-required-objective prerequisite, and a genuinely ambiguous one.
  // -------------------------------------------------------------------------------------------

  @Test
  void prerequisiteValidationResolvesFromTheRealCurriculumGraphNotANewRowWhenUnambiguous() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-prerequisite-validation-unambiguous");

    // KAFKA_ISR's only prerequisite, KAFKA_REPLICATION, is one of the ten unsplit skills -- exactly
    // one required objective, REPLICATION_FACTOR. ISR_TEST_ITEM is the test-only trigger, and the
    // REPLICATION_FACTOR candidate is a test-only item, both inserted in migrate() precisely because
    // neither KAFKA_ISR nor KAFKA_REPLICATION has any real content in V049's bank.
    ProbeResolution resolution =
        service.resolve(ISR_TEST_ITEM, ProbeRelationshipType.PREREQUISITE_VALIDATION, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.CANDIDATES_AVAILABLE);
    assertThat(resolution.hypothesis().targetObjectiveId()).isEqualTo(REPLICATION_FACTOR);
    // Read from skill_prerequisite/learning_objective, not diagnostic_probe_relationship.
    assertThat(resolution.hypothesis().authorizingRelationshipId()).isNull();
    assertThat(resolution.candidates()).extracting(ProbeCandidateItem::itemVersionId)
        .containsExactly(UUID.fromString("01900000-0000-7000-8000-000000000f04"));
  }

  @Test
  void prerequisiteValidationWithMultipleRealRequiredObjectivesIsAmbiguousNotSilentlyPickedByDisplayOrder() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-prerequisite-validation-ambiguous");

    // KAFKA_TOPIC's only prerequisite is KAFKA_BROKER -- one of the five skills with real content,
    // and so (H3) split into three required objectives: BROKER_STORAGE_MODEL, BROKER_CONTROLLER_ROLE,
    // BROKER_CLUSTER_OPERATIONS. All three are genuine candidates; none is diagnostically more
    // "correct" than another to pick by id or display_order.
    ProbeResolution resolution =
        service.resolve(TOPIC_MCQ_I2, ProbeRelationshipType.PREREQUISITE_VALIDATION, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.AMBIGUOUS_TARGET_OBJECTIVE);
    assertThat(resolution.hypothesis()).isNull();
    assertThat(resolution.candidates()).isEmpty();
    // display_order 1, 2, 3 respectively, per V052's own literal INSERT -- a single prerequisite
    // skill, so ordering is by objective display_order alone.
    assertThat(resolution.ambiguousTargetObjectiveIds()).containsExactly(
        BROKER_STORAGE_MODEL, BROKER_CONTROLLER_ROLE, BROKER_CLUSTER_OPERATIONS);
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
  // Ambiguity: two published ROOT_CAUSE_PROBE relationships from the same source objective, and a
  // trigger item tagged to more than one objective. Both reject arbitrary selection.
  // -------------------------------------------------------------------------------------------

  @Test
  void twoPublishedRootCauseProbeRelationshipsFromTheSameSourceAreReportedAsAmbiguous() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-ambiguous-root-cause");

    // ACKS_SEMANTICS (d10) has two PUBLISHED ROOT_CAUSE_PROBE rows in this fixture (see migrate()):
    // to PRODUCER_IDEMPOTENCE (d12) and to GROUP_PARTITION_ASSIGNMENT (d13). ACKS_MCQ_I1 is tagged
    // to d10 and used as a trigger nowhere else in this class.
    ProbeResolution resolution =
        service.resolve(ACKS_MCQ_I1, ProbeRelationshipType.ROOT_CAUSE_PROBE, learner.id());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.AMBIGUOUS_TARGET_OBJECTIVE);
    assertThat(resolution.hypothesis()).isNull();
    assertThat(resolution.candidates()).isEmpty();
    assertThat(resolution.ambiguousTargetObjectiveIds())
        .containsExactly(PRODUCER_IDEMPOTENCE, GROUP_PARTITION_ASSIGNMENT);
  }

  @Test
  void aTriggerItemTaggedToMoreThanOneObjectiveFailsClosedRatherThanPickingOne() {
    wire();
    Learner learner = learners.provisionForSubject("h4b-ambiguous-trigger-objective");

    // BROKER_MCQ_F is tagged to both BROKER_STORAGE_MODEL (its real, V049 tag) and
    // BROKER_CONTROLLER_ROLE (the test-only second tag added in migrate()) -- DiagnosticHypothesis
    // has a single triggerObjectiveId field, so there is no non-arbitrary one to resolve from.
    assertThatThrownBy(() ->
        service.resolve(BROKER_MCQ_F, ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION, learner.id()))
        .isInstanceOf(TriggerItemHasAmbiguousObjectiveException.class)
        .satisfies(exception -> assertThat(((TriggerItemHasAmbiguousObjectiveException) exception)
            .objectiveIds()).containsExactly(BROKER_STORAGE_MODEL, BROKER_CONTROLLER_ROLE));
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
