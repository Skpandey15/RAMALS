package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.evidence.EvidenceService;
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
 * V045 against real PostgreSQL: the form a learner was given is written down, is written down once,
 * and binds what they may afterwards answer.
 *
 * <p>Every claim here is one the mock-backed contract tests structurally cannot make. They assert
 * that the service calls the repository; these assert that PostgreSQL accepted the row, that the
 * constraint refused the row it should refuse, and that the trigger fired.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticFormPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT = UUID.fromString("01900000-0000-7000-8000-000000000401");
  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ITEM_BROKER = UUID.fromString("01900000-0000-7000-8000-000000000411");

  /** The published KAFKA v1 pool: five verified items across five distinct skills. */
  private static final int POOL_SIZE = 5;

  private static String databaseUrl;
  private AssessmentRepository assessments;
  private LearnerRepository learners;
  private DiagnosticService diagnostics;
  private DiagnosticSubmissionService submissions;
  private MasteryRepository masteryRepository;
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
  }

  // -------------------------------------------------------------------------------------------
  // The selection is persisted
  // -------------------------------------------------------------------------------------------

  @Test
  void attemptCreationPersistsExactlyTheItemsItSelected() {
    wire();
    UUID attemptId = diagnostics.createAttempt("v045-select", "kafka", "key-1").attempt().id();

    List<UUID> selectedItems = runtimeJdbc.queryForList("""
        SELECT item_version_id FROM core.assessment_attempt_item
        WHERE attempt_id = ? ORDER BY presentation_order
        """, UUID.class, attemptId);
    List<Integer> orders = runtimeJdbc.queryForList("""
        SELECT presentation_order FROM core.assessment_attempt_item
        WHERE attempt_id = ? ORDER BY presentation_order
        """, Integer.class, attemptId);

    // The whole pool, because five skills need five items to cover them -- and every one of them
    // written down at a position before the learner ever sees the attempt.
    assertThat(selectedItems).hasSize(POOL_SIZE).doesNotHaveDuplicates().contains(ITEM_BROKER);
    assertThat(orders).containsExactly(1, 2, 3, 4, 5);

    List<String> reasons = runtimeJdbc.queryForList(
        "SELECT DISTINCT selection_reason FROM core.assessment_attempt_item WHERE attempt_id = ?",
        String.class, attemptId);
    assertThat(reasons).containsExactly("SKILL_COVERAGE");

    // The attempt names the policy that assembled it, so a snapshot of this form can be explained
    // later without guessing which algorithm produced it.
    assertThat(runtimeJdbc.queryForObject(
        "SELECT selection_policy FROM core.assessment_attempt WHERE id = ?", String.class, attemptId))
        .isEqualTo(DiagnosticFormSelector.SELECTION_POLICY_VERSION);
  }

  @Test
  void theAttemptPresentsItsOwnFormInItsOwnOrder() {
    wire();
    UUID attemptId = diagnostics.createAttempt("v045-present", "kafka", "key-1").attempt().id();

    List<DiagnosticItem> presented = assessments.findPresentedItems(attemptId, versionOf(attemptId));
    List<Integer> persistedOrder = runtimeJdbc.queryForList("""
        SELECT presentation_order FROM core.assessment_attempt_item
        WHERE attempt_id = ? ORDER BY presentation_order
        """, Integer.class, attemptId);

    assertThat(presented).hasSize(POOL_SIZE);
    assertThat(presented).extracting(DiagnosticItem::displayOrder)
        .containsExactlyElementsOf(persistedOrder);
    // The read path carries the attempt's positions, not the authored display order of the pool.
    assertThat(presented).extracting(DiagnosticItem::id).doesNotHaveDuplicates();
  }

  // -------------------------------------------------------------------------------------------
  // The selection binds what may be answered
  // -------------------------------------------------------------------------------------------

  @Test
  void aResponseToAnItemThisAttemptNeverPresentedIsRefusedByTheDatabase() {
    wire();
    UUID attemptId = diagnostics.createAttempt("v045-unselected", "kafka", "key-1").attempt().id();
    UUID foreignItem = anUnselectedVerifiedItem();

    // The item exists and is a real assessment item; it simply was not on this learner's paper.
    // Answering it would produce evidence for a question nobody asked them, so the response guard
    // refuses it -- independently of the service-layer check, because this is where it becomes
    // permanent.
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.assessment_response
          (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"selectedOptions":["A"]}'::jsonb, true)
        """, UUID.randomUUID(), attemptId, foreignItem))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("was not selected for attempt");
  }

  @Test
  void theSubmissionServiceRejectsAnItemOutsideTheAttemptsForm() {
    wire();
    UUID attemptId = diagnostics.createAttempt("v045-svc", "kafka", "key-1").attempt().id();
    UUID foreignItem = anUnselectedVerifiedItem();

    DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(
        List.of(new ItemResponse(foreignItem.toString(), List.of("A"))));

    assertThatThrownBy(() -> transactionTemplate.execute(status ->
        submissions.submit("v045-svc", "kafka", attemptId.toString(), request)))
        .isInstanceOf(UnknownAssessmentItemException.class);

    // Refused before anything was written: the attempt is still open and holds no responses.
    assertThat(runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_response WHERE attempt_id = ?", Integer.class,
        attemptId)).isZero();
    assertThat(runtimeJdbc.queryForObject(
        "SELECT status FROM core.assessment_attempt WHERE id = ?", String.class, attemptId))
        .isEqualTo("IN_PROGRESS");
  }

  // -------------------------------------------------------------------------------------------
  // V045 triggers
  // -------------------------------------------------------------------------------------------

  @Test
  void aSelectedFormCannotBeRewrittenAfterTheFact() {
    wire();
    UUID attemptId = diagnostics.createAttempt("v045-immutable", "kafka", "key-1").attempt().id();

    // A form that can be edited after the learner has started is not a record of what they were
    // asked. Both mutations are refused by trg_assessment_attempt_item_guard.
    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.assessment_attempt_item SET presentation_order = 99 WHERE attempt_id = ?",
        attemptId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("selected assessment items are immutable");

    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.assessment_attempt_item WHERE attempt_id = ?", attemptId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("selected assessment items are immutable");

    assertThat(runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_attempt_item WHERE attempt_id = ?", Integer.class,
        attemptId)).isEqualTo(POOL_SIZE);
  }

  @Test
  void itemsCannotBeAddedToAnAttemptThatIsNoLongerOpen() {
    wire();
    UUID attemptId = diagnostics.createAttempt("v045-closed", "kafka", "key-1").attempt().id();
    submitEveryPresentedItem("v045-closed", attemptId);

    UUID foreignItem = anUnselectedVerifiedItem();
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 99, 'FILL')
        """, UUID.randomUUID(), attemptId, foreignItem))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("items may only be selected for an in-progress attempt");
  }

  @Test
  void oneItemCannotOccupyTwoPositionsAndTwoItemsCannotShareOne() {
    wire();
    UUID attemptId = diagnostics.createAttempt("v045-unique", "kafka", "key-1").attempt().id();

    // Duplicate item in the same form.
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 6, 'FILL')
        """, UUID.randomUUID(), attemptId, ITEM_BROKER))
        .isInstanceOf(DataAccessException.class);

    // Duplicate position in the same form.
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'FILL')
        """, UUID.randomUUID(), attemptId, anUnselectedVerifiedItem()))
        .isInstanceOf(DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  /**
   * A real, verified assessment item that no attempt of the published diagnostic selects.
   *
   * <p>The published KAFKA v1 pool is exactly one form long -- five items across five skills -- so
   * every attempt selects all of them and the pool offers no unselected item to test against. One
   * is created here on a DRAFT version, which V005 allows to be written to, so these tests can ask
   * the question the seeded content currently cannot: what happens to an item that exists but was
   * not on this learner's paper.
   */
  private UUID anUnselectedVerifiedItem() {
    UUID draftVersion = UUID.fromString("01900000-0000-7000-8000-0000000004d0");
    UUID draftItem = UUID.fromString("01900000-0000-7000-8000-0000000004d1");
    Integer exists = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_item_version WHERE id = ?", Integer.class, draftItem);
    if (exists != null && exists > 0) {
      return draftItem;
    }
    runtimeJdbc.update("""
        INSERT INTO core.assessment_version
          (id, assessment_id, curriculum_version_id, version_code, status)
        VALUES (?, ?, ?, 'draft-unselected', 'DRAFT')
        ON CONFLICT (id) DO NOTHING
        """, draftVersion, ASSESSMENT, CURRICULUM);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_item_version
          (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
           answer_key_jsonb, difficulty, display_order, trust_state, verified_by, verified_at)
        VALUES (?, ?, ?, 'KAFKA_DRAFT_UNSELECTED', 'SINGLE_CHOICE', 'Never selected.',
                '[{"id":"A","text":"a"},{"id":"B","text":"b"}]'::jsonb,
                '{"correct":["A"]}'::jsonb, 'FOUNDATIONAL', 1,
                'VERIFIED_CONTENT', 'integration-fixture', CURRENT_TIMESTAMP)
        ON CONFLICT (id) DO NOTHING
        """, draftItem, draftVersion, BROKER_SKILL);
    return draftItem;
  }

  private void submitEveryPresentedItem(String subject, UUID attemptId) {
    List<ItemResponse> responses = assessments.findPresentedItems(attemptId, versionOf(attemptId))
        .stream()
        .map(item -> new ItemResponse(item.id().toString(),
            List.of(item.options().getFirst().id())))
        .toList();
    // Evidence refuses to be written without provenance, so a submission needs a correlated
    // interaction the way a real request has one.
    MDC.put("interactionId", "01920000-0000-7000-8000-0000000000d1");
    try {
      transactionTemplate.execute(status -> submissions.submit(
          subject, "kafka", attemptId.toString(), new DiagnosticSubmissionRequest(responses)));
    } finally {
      MDC.remove("interactionId");
    }
  }

  private UUID versionOf(UUID attemptId) {
    return runtimeJdbc.queryForObject(
        "SELECT assessment_version_id FROM core.assessment_attempt WHERE id = ?", UUID.class,
        attemptId);
  }

  private void wire() {
    if (submissions == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      diagnostics = new DiagnosticService(assessments, learnerService,
          new DiagnosticFormSelector(new DiagnosticFormProperties()),
          new AdaptiveDiagnosticSelector(new AdaptiveDiagnosticFormProperties()), masteryRepository,
          new CurriculumService(new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator()),
          new ProbeRelationshipService(new ProbeRelationshipRepository(runtimeJdbc), assessments),
          new ProbeProvenanceRepository(runtimeJdbc));
      EvidenceRepository evidenceRepository = new EvidenceRepository(runtimeJdbc);
      MasteryService masteryService = new MasteryService(
          masteryRepository, evidenceRepository, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculatorV2(), new MasteryStatusPolicyV2());
      RecommendationService recommendationService = new RecommendationService(
          new RecommendationPolicy(), new RecommendationRepository(runtimeJdbc), learnerService);
      submissions = new DiagnosticSubmissionService(
          assessments, learnerService, new DiagnosticScorerV2(),
          new EvidenceService(evidenceRepository), masteryService, recommendationService,
          new DiagnosticConfidenceService(new ProbeProvenanceRepository(runtimeJdbc),
              new DiagnosticConfidenceRepository(runtimeJdbc), new DiagnosticConfidenceCalculatorV1()),
          new MisconceptionEvidenceCaptureService(new MisconceptionOptionMappingRepository(runtimeJdbc),
              new MisconceptionEvidenceObservationRepository(runtimeJdbc)),
          new MisconceptionConfidenceService(
              new MisconceptionConfidenceRepository(runtimeJdbc), new DiagnosticConfidenceCalculatorV1()),
          mapper);
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
