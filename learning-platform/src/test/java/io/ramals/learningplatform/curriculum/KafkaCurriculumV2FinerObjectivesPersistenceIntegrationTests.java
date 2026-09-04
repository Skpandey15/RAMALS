package io.ramals.learningplatform.curriculum;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.AssessmentRepository;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * V052's finer Kafka objectives against real PostgreSQL: the new curriculum version resolves with
 * the split objectives, the old one is provably unaffected, and -- the proof that actually
 * matters -- the finer tagging changes what {@code AssessmentRepository.findAttemptCoverage} (the
 * real read path evidence coverage is built from) reports, not just what the curriculum graph
 * displays.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class KafkaCurriculumV2FinerObjectivesPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID KAFKA_V2_ASSESSMENT_VERSION =
      UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID BROKER_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000601");
  private static final UUID BROKER_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000604");
  private static final UUID BROKER_STORAGE_MODEL =
      UUID.fromString("01900000-0000-7000-8000-000000000d01");
  private static final UUID BROKER_CONTROLLER_ROLE =
      UUID.fromString("01900000-0000-7000-8000-000000000d02");

  private static String databaseUrl;
  private CurriculumService curriculumService;
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

  @Test
  void theNewCurriculumVersionResolvesWithThreeObjectivesForBrokerAndOneForAnUnsplitSkill() {
    wire();
    CurriculumGraph graph = curriculumService.graph("KAFKA", "v2");

    CurriculumGraph.SkillNode broker = skill(graph, "KAFKA_BROKER");
    assertThat(broker.objectives()).extracting(CurriculumGraph.Objective::code)
        .containsExactlyInAnyOrder(
            "BROKER_STORAGE_MODEL", "BROKER_CONTROLLER_ROLE", "BROKER_CLUSTER_OPERATIONS");

    CurriculumGraph.SkillNode record = skill(graph, "KAFKA_RECORD");
    assertThat(record.objectives()).extracting(CurriculumGraph.Objective::code)
        .containsExactly("RECORD_ANATOMY"); // unsplit: same single objective v1 already had
  }

  @Test
  void theOldCurriculumVersionStillResolvesWithBrokersOriginalSingleObjective() {
    wire();
    CurriculumGraph graph = curriculumService.graph("KAFKA", "v1");

    CurriculumGraph.SkillNode broker = skill(graph, "KAFKA_BROKER");
    assertThat(broker.objectives()).extracting(CurriculumGraph.Objective::code)
        .containsExactly("BROKER_RESPONSIBILITY");
  }

  @Test
  void thePrerequisiteGraphIsIdenticalAcrossBothCurriculumVersions() {
    wire();
    CurriculumGraph v1 = curriculumService.graph("KAFKA", "v1");
    CurriculumGraph v2 = curriculumService.graph("KAFKA", "v2");

    assertThat(skill(v2, "KAFKA_TOPIC").prerequisiteSkillCodes())
        .isEqualTo(skill(v1, "KAFKA_TOPIC").prerequisiteSkillCodes())
        .containsExactly("KAFKA_BROKER");
    assertThat(skill(v2, "KAFKA_FAILURE_RECOVERY").prerequisiteSkillCodes())
        .isEqualTo(skill(v1, "KAFKA_FAILURE_RECOVERY").prerequisiteSkillCodes())
        .containsExactlyInAnyOrder("KAFKA_ISR", "KAFKA_REBALANCING");
  }

  @Test
  void findAttemptCoverageReportsTheFinerObjectivesNotTheOldSingleOne() {
    // The proof that matters: not just that the curriculum graph displays three objectives, but
    // that the actual read path evidence coverage is built from reports exactly which of the
    // three a real attempt's responses covered -- partial coverage is now representable, which it
    // structurally could not be under the old one-objective-per-skill scheme.
    wire();
    Learner learner = learners.provisionForSubject("v2-objective-coverage");
    UUID attemptId = insertAttempt(learner.id());
    presentItem(attemptId, BROKER_MCQ_F, 1);
    presentItem(attemptId, BROKER_MCQ_A1, 2);
    respond(attemptId, BROKER_MCQ_F);
    respond(attemptId, BROKER_MCQ_A1);

    Map<String, EvidenceCoverage> coverage = assessments.findAttemptCoverage(attemptId);

    EvidenceCoverage broker = coverage.get("KAFKA_BROKER");
    assertThat(broker).isNotNull();
    // Exactly the two objectives these two items were retagged to -- BROKER_CLUSTER_OPERATIONS,
    // the third objective, is absent because nothing in this attempt tested it.
    assertThat(broker.objectiveIds())
        .containsExactlyInAnyOrder(BROKER_STORAGE_MODEL, BROKER_CONTROLLER_ROLE);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private static CurriculumGraph.SkillNode skill(CurriculumGraph graph, String stableCode) {
    return graph.skills().stream()
        .filter(node -> node.stableCode().equals(stableCode))
        .findFirst().orElseThrow();
  }

  private UUID insertAttempt(UUID learnerId) {
    UUID id = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, id, learnerId, KAFKA_V2_ASSESSMENT_VERSION, "coverage-probe-" + id);
    return id;
  }

  private void presentItem(UUID attemptId, UUID itemVersionId, int presentationOrder) {
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, ?, 'SKILL_COVERAGE')
        """, UUID.randomUUID(), attemptId, itemVersionId, presentationOrder);
  }

  private void respond(UUID attemptId, UUID itemVersionId) {
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response
          (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"selectedOptions":["A"]}'::jsonb, true)
        """, UUID.randomUUID(), attemptId, itemVersionId);
  }

  private void wire() {
    if (curriculumService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      assessments = new AssessmentRepository(runtimeJdbc, JsonMapper.builder().build());
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
