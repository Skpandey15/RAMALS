package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.observability.UuidV7;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026) against real PostgreSQL and the real,
 * already-seeded KAFKA v2 curriculum/bank -- the same accepted pattern this suite's other
 * foundation-stage classes ({@code ProbeRelationshipResolverTests}, H4b's own #251) already
 * established: real content, no invented ids, foundation only -- nothing here is wired into
 * {@code DiagnosticService} or {@code DiagnosticSubmissionService}.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class GranularDiagnosticOntologyPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID KAFKA_DOMAIN = UUID.fromString("01900000-0000-7000-8000-000000000001");

  // Real, already-published (by this migration) vertical slice.
  private static final UUID ACKS_DURABILITY_TRADEOFFS = UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID CONCEPT_MIN_ISR = UUID.fromString("01900000-0000-7000-8000-000000000f01");
  private static final UUID SUB_CONCEPT_SINGLE_ISR_GAP = UUID.fromString("01900000-0000-7000-8000-000000000f02");
  private static final UUID MISCONCEPTION_ACKS_ALL_ALONE = UUID.fromString("01900000-0000-7000-8000-000000000f03");
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625"); // correct: B
  private static final UUID ACKS_FILL_F = UUID.fromString("01900000-0000-7000-8000-000000000627"); // FILL_BLANK

  private static String databaseUrl;
  private JdbcTemplate runtimeJdbc;
  private LearnerRepository learners;
  private DiagnosticNodeRepository nodes;
  private MisconceptionRepository misconceptions;
  private MisconceptionOptionMappingRepository mappings;
  private MisconceptionEvidenceService evidenceService;

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
  // Vertical slice: Objective -> Concept -> Sub-concept -> Misconception -> wrong-option mapping
  // -> learner response -> misconception evidence, over real, already-seeded KAFKA content.
  // -------------------------------------------------------------------------------------------

  @Test
  void theSeededConceptBelongsDirectlyToTheRealObjective() {
    wire();
    DiagnosticNode concept = nodes.findById(CONCEPT_MIN_ISR).orElseThrow();
    assertThat(concept.nodeType()).isEqualTo(DiagnosticNodeType.CONCEPT);
    assertThat(concept.objectiveId()).isEqualTo(ACKS_DURABILITY_TRADEOFFS);
    assertThat(concept.parentNodeId()).isNull();
  }

  @Test
  void theSeededSubConceptBelongsToTheConceptNotDirectlyToAnyObjective() {
    wire();
    DiagnosticNode subConcept = nodes.findById(SUB_CONCEPT_SINGLE_ISR_GAP).orElseThrow();
    assertThat(subConcept.nodeType()).isEqualTo(DiagnosticNodeType.SUB_CONCEPT);
    assertThat(subConcept.parentNodeId()).isEqualTo(CONCEPT_MIN_ISR);
    assertThat(subConcept.objectiveId()).isNull();
  }

  @Test
  void theSeededMisconceptionTargetsTheSubConcept() {
    wire();
    Misconception misconception = misconceptions.findById(MISCONCEPTION_ACKS_ALL_ALONE).orElseThrow();
    assertThat(misconception.targetDiagnosticNodeId()).isEqualTo(SUB_CONCEPT_SINGLE_ISR_GAP);
    assertThat(misconception.targetObjectiveId()).isNull();
  }

  @Test
  void aWrongAnswerTaggedToTheMisconceptionIsSupportingEvidence() {
    wire();
    UUID learnerId = learners.provisionForSubject("granular-supporting").id();
    UUID attemptId = completedAttemptWithOneResponse(learnerId, ACKS_MCQ_A1, "A", false);

    Optional<MisconceptionEvidenceOutcome> outcome =
        evidenceService.evidenceFor(MISCONCEPTION_ACKS_ALL_ALONE, attemptId, ACKS_MCQ_A1);

    assertThat(outcome).contains(MisconceptionEvidenceOutcome.SUPPORTING);
  }

  @Test
  void aCorrectAnswerIsContradictoryEvidence() {
    wire();
    UUID learnerId = learners.provisionForSubject("granular-contradictory").id();
    UUID attemptId = completedAttemptWithOneResponse(learnerId, ACKS_MCQ_A1, "B", true);

    Optional<MisconceptionEvidenceOutcome> outcome =
        evidenceService.evidenceFor(MISCONCEPTION_ACKS_ALL_ALONE, attemptId, ACKS_MCQ_A1);

    assertThat(outcome).contains(MisconceptionEvidenceOutcome.CONTRADICTORY);
  }

  @Test
  void aDifferentUntaggedWrongAnswerIsInconclusiveEvidence() {
    wire();
    UUID learnerId = learners.provisionForSubject("granular-inconclusive").id();
    // "C" ("min.insync.replicas only affects consumers") is a real wrong option on this item, but
    // was never tagged to any misconception -- wrong for a different, untagged reason.
    UUID attemptId = completedAttemptWithOneResponse(learnerId, ACKS_MCQ_A1, "C", false);

    Optional<MisconceptionEvidenceOutcome> outcome =
        evidenceService.evidenceFor(MISCONCEPTION_ACKS_ALL_ALONE, attemptId, ACKS_MCQ_A1);

    assertThat(outcome).contains(MisconceptionEvidenceOutcome.INCONCLUSIVE);
  }

  @Test
  void anItemWithNoPublishedMappingForTheMisconceptionProducesNoEvidence() {
    wire();
    UUID learnerId = learners.provisionForSubject("granular-not-eligible").id();
    UUID attemptId = completedAttemptWithOneResponse(learnerId, ACKS_MCQ_A1, "A", false);
    UUID unrelatedMisconceptionId = UUID.randomUUID();
    // Never inserted at all -- isEvidenceEligible must read this as "no PUBLISHED mapping exists",
    // not throw.

    Optional<MisconceptionEvidenceOutcome> outcome =
        evidenceService.evidenceFor(unrelatedMisconceptionId, attemptId, ACKS_MCQ_A1);

    assertThat(outcome).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // DB invariants: diagnostic_node hierarchy shape.
  // -------------------------------------------------------------------------------------------

  @Test
  void aThirdLevelOfNestingIsRejected() {
    wire();
    // A SUB_CONCEPT whose parent is itself a SUB_CONCEPT (not a CONCEPT) -- the guard trigger, not
    // a plain CHECK, must catch this.
    assertThatThrownBy(() -> nodes.insertSubConcept(
        UUID.randomUUID(), SUB_CONCEPT_SINGLE_ISR_GAP, "third level", "should be rejected", 1))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aConceptWithoutAnObjectiveIsRejected() {
    wire();
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.diagnostic_node (id, objective_id, parent_node_id, node_type, name, description, display_order)
        VALUES (?, NULL, NULL, 'CONCEPT', 'x', 'y', 1)
        """, UuidV7.generate()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aSubConceptWithoutAParentIsRejected() {
    wire();
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.diagnostic_node (id, objective_id, parent_node_id, node_type, name, description, display_order)
        VALUES (?, NULL, NULL, 'SUB_CONCEPT', 'x', 'y', 1)
        """, UuidV7.generate()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void publishedDiagnosticNodeIsImmutable() {
    wire();
    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.diagnostic_node SET name = 'renamed' WHERE id = ?", CONCEPT_MIN_ISR))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.diagnostic_node WHERE id = ?", SUB_CONCEPT_SINGLE_ISR_GAP))
        .isInstanceOf(DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // DB invariants: a published diagnostic node may never depend on a mutable DRAFT one. A DRAFT
  // SUB_CONCEPT may freely have a DRAFT parent CONCEPT (authoring the two together is normal), but
  // publishing must be blocked until the parent is itself PUBLISHED.
  // -------------------------------------------------------------------------------------------

  @Test
  void publishingASubConceptWhileItsParentConceptIsStillDraftIsRejected() {
    wire();
    UUID draftConceptId = UUID.randomUUID();
    UUID draftSubConceptId = UUID.randomUUID();
    nodes.insertConcept(draftConceptId, ACKS_DURABILITY_TRADEOFFS, "draft concept", "not yet published", 2);
    nodes.insertSubConcept(draftSubConceptId, draftConceptId, "draft sub-concept", "not yet published", 1);

    assertThatThrownBy(() -> nodes.publish(draftSubConceptId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void directlyInsertingAPublishedSubConceptWithADraftParentIsRejected() {
    wire();
    UUID draftConceptId = UUID.randomUUID();
    nodes.insertConcept(draftConceptId, ACKS_DURABILITY_TRADEOFFS, "draft concept", "not yet published", 3);

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.diagnostic_node
          (id, objective_id, parent_node_id, node_type, name, description, display_order, status, published_at)
        VALUES (?, NULL, ?, 'SUB_CONCEPT', 'x', 'y', 1, 'PUBLISHED', CURRENT_TIMESTAMP)
        """, UuidV7.generate(), draftConceptId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void publishingASubConceptSucceedsOnceItsParentConceptIsPublished() {
    wire();
    UUID conceptId = UUID.randomUUID();
    UUID subConceptId = UUID.randomUUID();
    nodes.insertConcept(conceptId, ACKS_DURABILITY_TRADEOFFS, "concept", "will be published", 4);
    nodes.insertSubConcept(subConceptId, conceptId, "sub-concept", "will be published", 1);

    nodes.publish(conceptId);
    nodes.publish(subConceptId); // must not throw, now that the parent is PUBLISHED

    assertThat(nodeStatus(subConceptId)).isEqualTo("PUBLISHED");
  }

  // -------------------------------------------------------------------------------------------
  // DB invariants: a published misconception may never depend on a mutable DRAFT target -- neither
  // a DRAFT diagnostic_node, nor an objective still sitting in an editable DRAFT curriculum_version
  // (reusing that table's own existing status column, not a new LearningObjective lifecycle rule).
  // -------------------------------------------------------------------------------------------

  @Test
  void publishingAMisconceptionWhileItsTargetNodeIsStillDraftIsRejected() {
    wire();
    UUID draftConceptId = UUID.randomUUID();
    nodes.insertConcept(draftConceptId, ACKS_DURABILITY_TRADEOFFS, "draft target concept", "not yet published", 5);
    UUID misconceptionId = UUID.randomUUID();
    misconceptions.insertTargetingNode(misconceptionId, "draft-target misconception", "not yet valid", draftConceptId);

    assertThatThrownBy(() -> misconceptions.publish(misconceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void directlyInsertingAPublishedMisconceptionWithADraftTargetNodeIsRejected() {
    wire();
    UUID draftConceptId = UUID.randomUUID();
    nodes.insertConcept(draftConceptId, ACKS_DURABILITY_TRADEOFFS, "draft target concept", "not yet published", 6);

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.misconception
          (id, name, description, target_objective_id, target_diagnostic_node_id, status, published_at)
        VALUES (?, 'x', 'y', NULL, ?, 'PUBLISHED', CURRENT_TIMESTAMP)
        """, UuidV7.generate(), draftConceptId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void publishingAMisconceptionSucceedsOnceItsTargetNodeIsPublished() {
    wire();
    UUID conceptId = UUID.randomUUID();
    nodes.insertConcept(conceptId, ACKS_DURABILITY_TRADEOFFS, "target concept", "will be published", 7);
    nodes.publish(conceptId);
    UUID misconceptionId = UUID.randomUUID();
    misconceptions.insertTargetingNode(misconceptionId, "now-valid misconception", "target is published", conceptId);

    misconceptions.publish(misconceptionId); // must not throw

    Misconception published = misconceptions.findById(misconceptionId).orElseThrow();
    assertThat(published.targetDiagnosticNodeId()).isEqualTo(conceptId);
  }

  @Test
  void aMisconceptionTargetingAnObjectiveInAStillDraftCurriculumCannotBePublished() {
    wire();
    UUID draftCurriculumVersionId = draftCurriculumWithOneObjective();
    UUID draftObjectiveId = onlyObjectiveOf(draftCurriculumVersionId);
    UUID misconceptionId = UUID.randomUUID();
    misconceptions.insertTargetingObjective(
        misconceptionId, "draft-curriculum-targeted misconception", "not yet valid", draftObjectiveId);

    assertThatThrownBy(() -> misconceptions.publish(misconceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aMisconceptionTargetingAnObjectiveInAnAlreadyPublishedCurriculumCanBePublished() {
    wire();
    // ACKS_DURABILITY_TRADEOFFS (d11) belongs to the real KAFKA v2 curriculum_version, already
    // PUBLISHED since V052 -- targeting it directly (no node) must be allowed to publish.
    UUID misconceptionId = UUID.randomUUID();
    misconceptions.insertTargetingObjective(
        misconceptionId, "objective-targeted misconception", "objective's curriculum is published",
        ACKS_DURABILITY_TRADEOFFS);

    misconceptions.publish(misconceptionId); // must not throw

    Misconception published = misconceptions.findById(misconceptionId).orElseThrow();
    assertThat(published.targetObjectiveId()).isEqualTo(ACKS_DURABILITY_TRADEOFFS);
  }

  // -------------------------------------------------------------------------------------------
  // DB invariants: misconception's exclusive-arc target.
  // -------------------------------------------------------------------------------------------

  @Test
  void aMisconceptionTargetingBothAnObjectiveAndANodeIsRejected() {
    wire();
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.misconception (id, name, description, target_objective_id, target_diagnostic_node_id)
        VALUES (?, 'x', 'y', ?, ?)
        """, UuidV7.generate(), ACKS_DURABILITY_TRADEOFFS, CONCEPT_MIN_ISR))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aMisconceptionTargetingNeitherAnObjectiveNorANodeIsRejected() {
    wire();
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.misconception (id, name, description, target_objective_id, target_diagnostic_node_id)
        VALUES (?, 'x', 'y', NULL, NULL)
        """, UuidV7.generate()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void publishedMisconceptionIsImmutable() {
    wire();
    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.misconception SET name = 'renamed' WHERE id = ?", MISCONCEPTION_ACKS_ALL_ALONE))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.misconception WHERE id = ?", MISCONCEPTION_ACKS_ALL_ALONE))
        .isInstanceOf(DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // DB invariants: wrong-option mapping.
  // -------------------------------------------------------------------------------------------

  @Test
  void aMappingAgainstANonSingleChoiceItemIsRejected() {
    wire();
    assertThatThrownBy(() -> mappings.insert(ACKS_FILL_F, "irrelevant", MISCONCEPTION_ACKS_ALL_ALONE))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aMappingAgainstANonexistentOptionIsRejected() {
    wire();
    assertThatThrownBy(() -> mappings.insert(ACKS_MCQ_A1, "Z", MISCONCEPTION_ACKS_ALL_ALONE))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aMappingAgainstTheCorrectOptionIsRejected() {
    wire();
    // "B" is ACKS_MCQ_A1's real correct answer.
    assertThatThrownBy(() -> mappings.insert(ACKS_MCQ_A1, "B", MISCONCEPTION_ACKS_ALL_ALONE))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aMappingCannotPublishBeforeItsMisconceptionIsPublished() {
    wire();
    UUID draftMisconceptionId = UUID.randomUUID();
    misconceptions.insertTargetingObjective(
        draftMisconceptionId, "draft misconception", "not yet published", ACKS_DURABILITY_TRADEOFFS);
    mappings.insert(ACKS_MCQ_A1, "C", draftMisconceptionId);

    assertThatThrownBy(() -> mappings.publish(ACKS_MCQ_A1, "C", draftMisconceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void publishedMappingIsImmutable() {
    wire();
    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.assessment_item_option_misconception SET status = 'DRAFT' "
            + "WHERE item_version_id = ? AND option_id = ? AND misconception_id = ?",
        ACKS_MCQ_A1, "A", MISCONCEPTION_ACKS_ALL_ALONE))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.assessment_item_option_misconception "
            + "WHERE item_version_id = ? AND option_id = ? AND misconception_id = ?",
        ACKS_MCQ_A1, "A", MISCONCEPTION_ACKS_ALL_ALONE))
        .isInstanceOf(DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID completedAttemptWithOneResponse(
      UUID learnerId, UUID itemVersionId, String selectedOption, boolean isCorrect) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "granular-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, ?::jsonb, ?)
        """, UUID.randomUUID(), attemptId, itemVersionId,
        "{\"selectedOptions\":[\"" + selectedOption + "\"]}", isCorrect);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
    return attemptId;
  }

  private String nodeStatus(UUID nodeId) {
    return runtimeJdbc.queryForObject(
        "SELECT status FROM core.diagnostic_node WHERE id = ?", String.class, nodeId);
  }

  /** A fresh curriculum_version, skill, skill_version, and its one learning_objective -- left in
   * the default DRAFT status (never published), so a misconception targeting its objective cannot
   * yet be published either. Reuses the real KAFKA domain (any domain_id would do; skill/curriculum
   * identity is what needs to be fresh per call). */
  private UUID draftCurriculumWithOneObjective() {
    UUID curriculumVersionId = UUID.randomUUID();
    String suffix = curriculumVersionId.toString().substring(0, 8);
    runtimeJdbc.update("""
        INSERT INTO core.curriculum_version (id, domain_id, version_code)
        VALUES (?, ?, ?)
        """, curriculumVersionId, KAFKA_DOMAIN, "draft-test-" + suffix);

    UUID skillId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.skill (id, domain_id, stable_code)
        VALUES (?, ?, ?)
        """, skillId, KAFKA_DOMAIN, "DRAFT_TEST_SKILL_" + suffix.toUpperCase());

    UUID skillVersionId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.skill_version
          (id, skill_id, curriculum_version_id, title, description, difficulty,
           estimated_learning_minutes, display_order)
        VALUES (?, ?, ?, 'Draft test skill', 'A skill in a still-DRAFT curriculum version.',
                'FOUNDATIONAL', 10, 1)
        """, skillVersionId, skillId, curriculumVersionId);

    UUID objectiveId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.learning_objective (id, skill_version_id, objective_code, description, display_order)
        VALUES (?, ?, 'DRAFT_TEST_OBJECTIVE', 'An objective in a still-DRAFT curriculum version.', 1)
        """, objectiveId, skillVersionId);

    return curriculumVersionId;
  }

  private UUID onlyObjectiveOf(UUID curriculumVersionId) {
    return runtimeJdbc.queryForObject("""
        SELECT lo.id FROM core.learning_objective lo
        JOIN core.skill_version sv ON sv.id = lo.skill_version_id
        WHERE sv.curriculum_version_id = ?
        """, UUID.class, curriculumVersionId);
  }

  private void wire() {
    if (evidenceService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      learners = new LearnerRepository(runtimeJdbc);
      nodes = new DiagnosticNodeRepository(runtimeJdbc);
      misconceptions = new MisconceptionRepository(runtimeJdbc);
      mappings = new MisconceptionOptionMappingRepository(runtimeJdbc);
      evidenceService = new MisconceptionEvidenceService(mappings);
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
