package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.AssessmentRepository;
import tools.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Trust state against real PostgreSQL.
 *
 * <p>The database constraints are the strongest guarantee in M1-ADR-006, and the only one that
 * survives a future caller who does not go through the service layer. Authorization can be bypassed
 * by an internal caller; a CHECK constraint cannot. So these tests attempt the forbidden writes
 * directly, as SQL, rather than asserting that the Java refuses to attempt them.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ContentTrustPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  /** The seeded Kafka diagnostic from V005, PUBLISHED and therefore immutable. */
  private static final UUID PUBLISHED_VERSION =
      UUID.fromString("01900000-0000-7000-8000-000000000402");

  private static final UUID KAFKA_ASSESSMENT =
      UUID.fromString("01900000-0000-7000-8000-000000000401");
  private static final UUID KAFKA_CURRICULUM_VERSION =
      UUID.fromString("01900000-0000-7000-8000-000000000002");

  private static String databaseUrl;
  private JdbcTemplate migrationJdbc;
  private ContentTrustRepository repository;
  private AssessmentRepository assessments;

  /**
   * A fresh draft version per test, because that is the only place generated content can go.
   *
   * <p>V005 makes items of a published version immutable to INSERT as well as UPDATE, so a generator
   * cannot append candidates to a live diagnostic even before trust state is considered. Candidate
   * content therefore accumulates in a draft version and the version publishes as a unit.
   */
  private UUID draftVersion;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = required("RAMALS_TEST_POSTGRES_URL");
    String adminUser = required("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, required("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String database = statement.enquoteIdentifier(currentDatabase(statement), true);
      String admin = statement.enquoteIdentifier(adminUser, true);
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
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + admin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + database + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }

    Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for this integration test");
    }
    return value;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (var result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  @BeforeEach
  void setUp() {
    migrationJdbc = new JdbcTemplate(dataSource(MIGRATION_USER, MIGRATION_PASSWORD));
    JdbcTemplate runtimeJdbc = new JdbcTemplate(dataSource(RUNTIME_USER, RUNTIME_PASSWORD));
    repository = new ContentTrustRepository(runtimeJdbc);
    assessments = new AssessmentRepository(runtimeJdbc, new ObjectMapper());

    draftVersion = UUID.randomUUID();
    migrationJdbc.update("""
        INSERT INTO core.assessment_version
          (id, assessment_id, curriculum_version_id, version_code, status)
        VALUES (?, ?, ?, ?, 'DRAFT')
        """, draftVersion, KAFKA_ASSESSMENT, KAFKA_CURRICULUM_VERSION, "draft-" + draftVersion);
  }

  private DriverManagerDataSource dataSource(String user, String password) {
    DriverManagerDataSource source = new DriverManagerDataSource(databaseUrl, user, password);
    source.setDriverClassName("org.postgresql.Driver");
    return source;
  }

  /** Inserts a candidate item the way a generator would, taking the column default. */
  private UUID insertCandidate(String itemCode, int displayOrder) {
    return insertCandidateInto(draftVersion, itemCode, displayOrder);
  }

  private UUID insertCandidateInto(UUID assessmentVersionId, String itemCode, int displayOrder) {
    UUID id = UUID.randomUUID();
    migrationJdbc.update("""
        INSERT INTO core.assessment_item_version
          (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
           answer_key_jsonb, difficulty, display_order)
        SELECT ?, ?, s.id, ?, 'SINGLE_CHOICE', 'generated stem',
               '[{"id":"A","text":"An ordered log"},{"id":"B","text":"A broker"}]'::jsonb,
               '{"correct":["A"]}'::jsonb, 'FOUNDATIONAL', ?
          FROM core.skill s WHERE s.stable_code = 'KAFKA_TOPIC'
        """, id, assessmentVersionId, itemCode, displayOrder);
    return id;
  }

  private void publish(UUID assessmentVersionId) {
    migrationJdbc.update("""
        UPDATE core.assessment_version SET status = 'PUBLISHED' WHERE id = ?
        """, assessmentVersionId);
  }

  // -- created UNVERIFIED -----------------------------------------------------------------------

  @Test
  @DisplayName("content created without a trust state defaults to UNVERIFIED")
  void newContentIsUnverified() {
    UUID id = insertCandidate("GEN_ITEM_A", 900);

    // A generator that forgets to set a trust state produces content that cannot reach a learner.
    // That is the correct direction for the mistake to fail.
    assertThat(repository.trustStateOf(id)).contains(TrustState.UNVERIFIED);
  }

  @Test
  @DisplayName("the pre-existing hand-authored curriculum is verified and attributed")
  void seededCurriculumIsVerified() {
    String reviewer = migrationJdbc.queryForObject("""
        SELECT verified_by FROM core.assessment_item_version
         WHERE assessment_version_id = ? AND trust_state = 'VERIFIED_CONTENT' LIMIT 1
        """, String.class, PUBLISHED_VERSION);

    assertThat(reviewer).isEqualTo("mvp0-curriculum-authoring");
  }

  @Test
  @DisplayName("the live diagnostic did not lose items when the trust filter arrived")
  void theSeededDiagnosticStillServesEveryItem() {
    // The regression that matters most on this migration: filtering selection to VERIFIED_CONTENT
    // would silently shorten the published Kafka diagnostic if the backfill had missed a row.
    Integer authored = migrationJdbc.queryForObject("""
        SELECT count(*) FROM core.assessment_item_version WHERE assessment_version_id = ?
        """, Integer.class, PUBLISHED_VERSION);

    assertThat(assessments.findItems(PUBLISHED_VERSION)).hasSize(authored);
  }

  @Test
  @DisplayName("a generator cannot append candidates to a published version at all")
  void publishedVersionsRefuseNewItems() {
    // Trust state is not the first line of defence here -- V005 immutability is. Recorded because it
    // is the reason candidate content lives in a draft version rather than beside live items.
    assertThatThrownBy(() -> insertCandidateInto(PUBLISHED_VERSION, "GEN_ITEM_LIVE", 990))
        .hasMessageContaining("immutable");
  }

  // -- the constraints, attempted directly ---------------------------------------------------------

  @Test
  @DisplayName("VERIFIED_CONTENT cannot be written without naming a reviewer")
  void verifiedRequiresAReviewer() {
    UUID id = insertCandidate("GEN_ITEM_B", 901);

    // Attempted as raw SQL on purpose. This is the guarantee that survives a caller who never goes
    // near ContentPromotionService.
    assertThatThrownBy(() -> migrationJdbc.update("""
        UPDATE core.assessment_item_version SET trust_state = 'VERIFIED_CONTENT' WHERE id = ?
        """, id))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(repository.trustStateOf(id)).contains(TrustState.UNVERIFIED);
  }

  @Test
  @DisplayName("a blank reviewer is not a reviewer")
  void blankReviewerIsRefused() {
    UUID id = insertCandidate("GEN_ITEM_C", 902);

    assertThatThrownBy(() -> migrationJdbc.update("""
        UPDATE core.assessment_item_version
           SET trust_state = 'VERIFIED_CONTENT', verified_by = '   ', verified_at = now()
         WHERE id = ?
        """, id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("REJECTED cannot be written without naming the stage that refused")
  void rejectedRequiresAStage() {
    UUID id = insertCandidate("GEN_ITEM_D", 903);

    assertThatThrownBy(() -> migrationJdbc.update("""
        UPDATE core.assessment_item_version SET trust_state = 'REJECTED' WHERE id = ?
        """, id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("an unknown trust state cannot be written")
  void unknownTrustStateIsRefused() {
    UUID id = insertCandidate("GEN_ITEM_E", 904);

    assertThatThrownBy(() -> migrationJdbc.update("""
        UPDATE core.assessment_item_version SET trust_state = 'PROBABLY_FINE' WHERE id = ?
        """, id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -- transitions -----------------------------------------------------------------------------------

  @Test
  @DisplayName("promotion records the reviewer and clears any rejection")
  void promotionRecordsTheReviewer() {
    UUID id = insertCandidate("GEN_ITEM_F", 905);

    repository.promote(id, "reviewer-subject-1");

    assertThat(repository.trustStateOf(id)).contains(TrustState.VERIFIED_CONTENT);
    assertThat(repository.reviewerOf(id)).contains("reviewer-subject-1");
    assertThat(repository.rejectedAtStage(id)).isEmpty();
  }

  @Test
  @DisplayName("rejection records which stage refused")
  void rejectionRecordsTheStage() {
    UUID id = insertCandidate("GEN_ITEM_G", 906);

    repository.reject(id, ValidationStage.QUALITY_SAFETY, "duplicate options");

    assertThat(repository.trustStateOf(id)).contains(TrustState.REJECTED);
    assertThat(repository.rejectedAtStage(id)).contains(ValidationStage.QUALITY_SAFETY);
  }

  @Test
  @DisplayName("promotion does not resurrect rejected content")
  void promotionCannotOverwriteARejection() {
    UUID id = insertCandidate("GEN_ITEM_H", 907);
    repository.reject(id, ValidationStage.STRUCTURAL, "no options");

    repository.promote(id, "reviewer-subject-2");

    // The UPDATE pins the source state, so a promotion that read a stale UNVERIFIED cannot silently
    // win a race against a rejection.
    assertThat(repository.trustStateOf(id)).contains(TrustState.REJECTED);
  }

  // -- selection safety --------------------------------------------------------------------------------

  @Test
  @DisplayName("unverified content is not offered to a learner")
  void unverifiedContentIsNotSelected() {
    UUID unverified = insertCandidate("GEN_ITEM_I", 908);

    // Asserted with the unverified row genuinely present, so the test cannot pass because nothing
    // was there to exclude.
    assertThat(repository.countInState(draftVersion, TrustState.UNVERIFIED))
        .isGreaterThan(0);

    assertThat(assessments.findItems(draftVersion))
        .extracting(item -> item.id())
        .doesNotContain(unverified);
  }

  @Test
  @DisplayName("unverified content cannot become evidence, because scoring never sees it")
  void unverifiedContentCannotProduceEvidence() {
    UUID unverified = insertCandidate("GEN_ITEM_J", 909);

    // The scoring path is the one that decides correctness and therefore creates evidence. An item
    // reaching only this query would produce evidence for something no learner was ever shown.
    assertThat(assessments.findItemScoringViews(draftVersion))
        .extracting(view -> view.itemVersionId())
        .doesNotContain(unverified);
  }

  @Test
  @DisplayName("rejected content is not offered either")
  void rejectedContentIsNotSelected() {
    UUID rejected = insertCandidate("GEN_ITEM_K", 910);
    repository.reject(rejected, ValidationStage.DETERMINISTIC_POLICY, "unknown skill");

    assertThat(assessments.findItems(draftVersion))
        .extracting(item -> item.id())
        .doesNotContain(rejected);
  }

  @Test
  @DisplayName("promoted content becomes selectable")
  void promotedContentIsSelected() {
    // The converse. A filter that excluded everything would pass every test above and serve no
    // learner anything.
    UUID promoted = insertCandidate("GEN_ITEM_L", 911);
    repository.promote(promoted, "reviewer-subject-3");

    assertThat(assessments.findItems(draftVersion))
        .extracting(item -> item.id())
        .contains(promoted);
  }

  // -- publication is the other way content reaches a learner -------------------------------------

  @Test
  @DisplayName("a version with unverified items cannot be published")
  void publishingRefusesUnverifiedItems() {
    insertCandidate("GEN_ITEM_M", 912);

    // Without this gate the version would publish and simply serve fewer items than it contains --
    // a measurement quietly taken over less evidence than it claims, with nothing logged.
    assertThatThrownBy(() -> publish(draftVersion))
        .hasMessageContaining("not VERIFIED_CONTENT");

    assertThat(migrationJdbc.queryForObject(
        "SELECT status FROM core.assessment_version WHERE id = ?", String.class, draftVersion))
        .isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("a version with rejected items cannot be published either")
  void publishingRefusesRejectedItems() {
    UUID verified = insertCandidate("GEN_ITEM_N", 913);
    repository.promote(verified, "reviewer-subject-4");
    UUID rejected = insertCandidate("GEN_ITEM_O", 914);
    repository.reject(rejected, ValidationStage.QUALITY_SAFETY, "duplicate options");

    // One bad item is enough. Publishing the rest and dropping this one would be the same silent
    // shortening, just with a paper trail explaining why the item was dropped.
    assertThatThrownBy(() -> publish(draftVersion))
        .hasMessageContaining("not VERIFIED_CONTENT");
  }

  @Test
  @DisplayName("a fully verified version publishes")
  void publishingSucceedsOnceEveryItemIsVerified() {
    // The converse. A gate that refused everything would satisfy both tests above and ship nothing.
    UUID first = insertCandidate("GEN_ITEM_P", 915);
    UUID second = insertCandidate("GEN_ITEM_Q", 916);
    repository.promote(first, "reviewer-subject-5");
    repository.promote(second, "reviewer-subject-5");

    publish(draftVersion);

    assertThat(migrationJdbc.queryForObject(
        "SELECT status FROM core.assessment_version WHERE id = ?", String.class, draftVersion))
        .isEqualTo("PUBLISHED");
  }
}
