package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * V047 and V048 against real PostgreSQL: SHORT_ANSWER and USE_CASE content cannot reach a learner
 * form despite being VERIFIED_CONTENT and lineaged, and the no-repeat exclusion PR-B will build on
 * resolves through logical identity -- not {@code item_version_id} and not {@code item_code}
 * convention -- so an editorial revision of a question a learner already saw still reads as seen.
 *
 * <p>Both claims need a real database. The type filter is SQL in {@code AssessmentRepository}, and
 * the exposure-history join walks {@code assessment_attempt_item -> assessment_item_lineage} across
 * real rows -- neither is provable by a mock.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class AssessmentItemLineagePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT = UUID.fromString("01900000-0000-7000-8000-000000000401");
  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");

  private static String databaseUrl;
  private AssessmentRepository assessments;
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
  // Unscoreable content cannot reach a learner form.
  // -------------------------------------------------------------------------------------------

  @Test
  void unscoreableTypesNeverReachEligibleItemsOrPresentedItems() {
    wire();
    UUID draftVersion = freshDraftVersion("unscoreable-probe");
    UUID mcqItem = insertItem(draftVersion, "PROBE_MCQ", "SINGLE_CHOICE", 1,
        "[{\"id\":\"A\",\"text\":\"a\"},{\"id\":\"B\",\"text\":\"b\"}]", "{\"correct\":[\"A\"]}");
    UUID shortAnswerItem = insertItem(draftVersion, "PROBE_SHORT_ANSWER", "SHORT_ANSWER", 2,
        "[]", "{\"rubric\":{\"dimensions\":[]}}");
    UUID useCaseItem = insertItem(draftVersion, "PROBE_USE_CASE", "USE_CASE", 3,
        "[]", "{\"rubric\":{\"dimensions\":[]}}");
    // All three are equally VERIFIED_CONTENT and equally lineaged. The only thing that can be
    // keeping the latter two out is the item_type filter itself.
    lineage(mcqItem);
    lineage(shortAnswerItem);
    lineage(useCaseItem);

    UUID learnerId = learners.provisionForSubject("lineage-unscoreable-probe").id();
    List<EligibleItem> eligible = assessments.findEligibleItems(
        draftVersion, learnerId, Instant.now().minusSeconds(3600));
    assertThat(eligible).extracting(EligibleItem::itemVersionId).containsExactly(mcqItem);

    List<DiagnosticItem> presentable = assessments.findItems(draftVersion);
    assertThat(presentable).extracting(DiagnosticItem::id).containsExactly(mcqItem);
  }

  // -------------------------------------------------------------------------------------------
  // Exposure history resolves through logical identity, across versions, per learner.
  // -------------------------------------------------------------------------------------------

  @Test
  void exposureHistoryResolvesAcrossVersionsSharingALogicalIdentityAndIsLearnerIsolated() {
    wire();
    UUID draftVersion = freshDraftVersion("lineage-exposure-probe");
    // Two item VERSIONS, standing in for a question and an editorial revision of it: different
    // item_version_id, different item_code, same logical_item_id.
    UUID originalVersion = insertItem(draftVersion, "PROBE_ORIGINAL", "SINGLE_CHOICE", 1,
        "[{\"id\":\"A\",\"text\":\"a\"},{\"id\":\"B\",\"text\":\"b\"}]", "{\"correct\":[\"A\"]}");
    UUID revisedVersion = insertItem(draftVersion, "PROBE_REVISED", "SINGLE_CHOICE", 2,
        "[{\"id\":\"A\",\"text\":\"a, reworded\"},{\"id\":\"B\",\"text\":\"b, reworded\"}]",
        "{\"correct\":[\"A\"]}");
    UUID sharedLogicalId = UUID.randomUUID();
    lineage(originalVersion, sharedLogicalId);
    lineage(revisedVersion, sharedLogicalId);

    Learner learnerA = learners.provisionForSubject("lineage-exposure-a");
    Learner learnerB = learners.provisionForSubject("lineage-exposure-b");

    // Learner A was presented the ORIGINAL version, in an attempt of their own.
    UUID attemptId = insertAttempt(learnerA.id(), draftVersion);
    presentItem(attemptId, originalVersion, 1);

    Set<UUID> exposedToA = assessments.findLearnerExposedLogicalItemIds(learnerA.id());
    Set<UUID> exposedToB = assessments.findLearnerExposedLogicalItemIds(learnerB.id());

    // A saw only the original version, by item_version_id -- but the logical identity it resolves
    // to is what a REVISED version would also resolve to, so a no-repeat check keyed on this set
    // would correctly refuse to offer learner A either the original OR the revised version again.
    assertThat(exposedToA).containsExactly(sharedLogicalId);
    // Learner B was never presented anything. Their history must be empty regardless of what A saw
    // -- exposure is per learner, not global.
    assertThat(exposedToB).isEmpty();
  }

  @Test
  void exposureHistoryReflectsCompleteHistoryNotOnlyTheMostRecentAttempt() {
    wire();
    UUID draftVersion = freshDraftVersion("lineage-complete-history-probe");
    UUID first = insertItem(draftVersion, "PROBE_FIRST", "SINGLE_CHOICE", 1,
        "[{\"id\":\"A\",\"text\":\"a\"},{\"id\":\"B\",\"text\":\"b\"}]", "{\"correct\":[\"A\"]}");
    UUID second = insertItem(draftVersion, "PROBE_SECOND", "SINGLE_CHOICE", 2,
        "[{\"id\":\"A\",\"text\":\"a\"},{\"id\":\"B\",\"text\":\"b\"}]", "{\"correct\":[\"A\"]}");
    UUID firstLogical = UUID.randomUUID();
    UUID secondLogical = UUID.randomUUID();
    lineage(first, firstLogical);
    lineage(second, secondLogical);

    Learner learner = learners.provisionForSubject("lineage-complete-history");
    UUID earlierAttempt = insertAttempt(learner.id(), draftVersion);
    presentItem(earlierAttempt, first, 1);
    // Completed before the second attempt starts: core.assessment_attempt enforces at most one
    // IN_PROGRESS attempt per learner and version (uq_assessment_attempt_one_active), so two
    // attempts against the same version can only coexist in history once the earlier one has
    // finished -- which is the real shape a learner's attempt history takes.
    completeAttempt(earlierAttempt);
    UUID laterAttempt = insertAttempt(learner.id(), draftVersion);
    presentItem(laterAttempt, second, 1);

    // Both attempts' items are in the exposure set, not only the most recent attempt's -- the
    // no-repeat guarantee this supports explicitly must not stop at "the immediately previous
    // attempt".
    assertThat(assessments.findLearnerExposedLogicalItemIds(learner.id()))
        .containsExactlyInAnyOrder(firstLogical, secondLogical);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID freshDraftVersion(String versionCode) {
    UUID id = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_version (id, assessment_id, curriculum_version_id, version_code, status)
        VALUES (?, ?, ?, ?, 'DRAFT')
        """, id, ASSESSMENT, CURRICULUM, versionCode);
    return id;
  }

  private UUID insertItem(
      UUID versionId, String itemCode, String itemType, int displayOrder,
      String optionsJson, String answerKeyJson) {
    UUID id = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_item_version
          (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
           answer_key_jsonb, difficulty, display_order, trust_state, verified_by, verified_at)
        VALUES (?, ?, ?, ?, ?, 'Probe stem.', ?::jsonb, ?::jsonb, 'FOUNDATIONAL', ?,
                'VERIFIED_CONTENT', 'integration-fixture', CURRENT_TIMESTAMP)
        """, id, versionId, BROKER_SKILL, itemCode, itemType, optionsJson, answerKeyJson,
        displayOrder);
    return id;
  }

  private void lineage(UUID itemVersionId) {
    lineage(itemVersionId, UUID.randomUUID());
  }

  private void lineage(UUID itemVersionId, UUID logicalItemId) {
    runtimeJdbc.update(
        "INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id) VALUES (?, ?)",
        itemVersionId, logicalItemId);
  }

  private UUID insertAttempt(UUID learnerId, UUID versionId) {
    UUID id = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, id, learnerId, versionId, "lineage-probe-" + id);
    return id;
  }

  private void completeAttempt(UUID attemptId) {
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
  }

  private void presentItem(UUID attemptId, UUID itemVersionId, int presentationOrder) {
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, ?, 'SKILL_COVERAGE')
        """, UUID.randomUUID(), attemptId, itemVersionId, presentationOrder);
  }

  private void wire() {
    if (assessments == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
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
