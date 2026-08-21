package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.EvidenceConfidenceCalculator;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.mastery.MasteryStatusPolicy;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * End-to-end diagnostic submission against real PostgreSQL as the runtime role: deterministic
 * scoring vectors, idempotent duplicate submit, transactional rollback leaving no partial state,
 * and response immutability once the attempt is completed.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticSubmissionPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ITEM_BROKER = UUID.fromString("01900000-0000-7000-8000-000000000411");
  private static final UUID ITEM_TOPIC = UUID.fromString("01900000-0000-7000-8000-000000000412");
  private static final UUID ITEM_ABSENT = UUID.fromString("01900000-0000-7000-8000-0000000004ff");

  private static String databaseUrl;
  private AssessmentRepository assessments;
  private LearnerRepository learners;
  private DiagnosticService diagnostics;
  private DiagnosticSubmissionService submissions;
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
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
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

  private void wire() {
    if (submissions == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      diagnostics = new DiagnosticService(assessments, learnerService);
      EvidenceRepository evidenceRepository = new EvidenceRepository(runtimeJdbc);
      EvidenceService evidenceService = new EvidenceService(evidenceRepository);
      MasteryService masteryService = new MasteryService(
          new MasteryRepository(runtimeJdbc), evidenceRepository, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculator(), new MasteryStatusPolicy());
      RecommendationService recommendationService = new RecommendationService(
          new RecommendationPolicy(), new RecommendationRepository(runtimeJdbc), learnerService, event -> { });
      submissions = new DiagnosticSubmissionService(
          assessments, learnerService, new DiagnosticScorer(), evidenceService, masteryService,
          recommendationService, mapper);
      transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }
  }

  private UUID createAttempt(String subject) {
    return diagnostics.createAttempt(subject, "kafka", "key-1").attempt().id();
  }

  private SubmissionResult submit(String subject, UUID attemptId, DiagnosticSubmissionRequest request) {
    MDC.put("interactionId", "01920000-0000-7000-8000-0000000000e1");
    try {
      return transactionTemplate.execute(
          status -> submissions.submit(subject, "kafka", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }
  }

  private long responseCount(UUID attemptId) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_response WHERE attempt_id = ?", Long.class, attemptId);
  }

  private String attemptStatus(UUID attemptId) {
    return runtimeJdbc.queryForObject(
        "SELECT status FROM core.assessment_attempt WHERE id = ?", String.class, attemptId);
  }

  @Test
  void submissionScoresDeterministicallyAndFinalizes() {
    wire();
    UUID attemptId = createAttempt("pg-submit");
    SubmissionResult result = submit("pg-submit", attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ITEM_BROKER.toString(), List.of("B")),   // seed answer key: correct
        new ItemResponse(ITEM_TOPIC.toString(), List.of("A")))));  // seed answer key: incorrect

    assertThat(result.attempt().status()).isEqualTo("COMPLETED");
    assertThat(result.itemsAnswered()).isEqualTo(2);
    Map<String, SkillScore> bySkill = result.skillScores().stream()
        .collect(Collectors.toMap(SkillScore::skillCode, Function.identity()));
    assertThat(bySkill.get("KAFKA_BROKER").normalizedScore()).isEqualByComparingTo("1.0000");
    assertThat(bySkill.get("KAFKA_TOPIC").normalizedScore()).isEqualByComparingTo("0.0000");
    assertThat(responseCount(attemptId)).isEqualTo(2);
    assertThat(attemptStatus(attemptId)).isEqualTo("COMPLETED");
  }

  @Test
  void duplicateSubmitIsIdempotentAndAddsNoRows() {
    wire();
    UUID attemptId = createAttempt("pg-dupe");
    DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ITEM_BROKER.toString(), List.of("B"))));

    SubmissionResult first = submit("pg-dupe", attemptId, request);
    SubmissionResult replay = submit("pg-dupe", attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ITEM_TOPIC.toString(), List.of("C")))));  // ignored: attempt already done

    assertThat(first.itemsAnswered()).isEqualTo(1);
    assertThat(replay.itemsAnswered()).isEqualTo(1);
    assertThat(replay.skillScores()).extracting(SkillScore::skillCode).containsExactly("KAFKA_BROKER");
    assertThat(responseCount(attemptId)).isEqualTo(1);
  }

  @Test
  void failedSubmissionRollsBackLeavingNoPartialState() {
    wire();
    UUID attemptId = createAttempt("pg-rollback");

    assertThatThrownBy(() -> submit("pg-rollback", attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ITEM_BROKER.toString(), List.of("B")),   // valid, inserted first
        new ItemResponse(ITEM_ABSENT.toString(), List.of("B")))))) // unknown -> aborts
        .isInstanceOf(UnknownAssessmentItemException.class);

    assertThat(responseCount(attemptId)).isZero();
    assertThat(attemptStatus(attemptId)).isEqualTo("IN_PROGRESS");
  }

  @Test
  void completedAttemptResponsesAreImmutable() {
    wire();
    UUID attemptId = createAttempt("pg-immutable");
    submit("pg-immutable", attemptId, new DiagnosticSubmissionRequest(List.of(
        new ItemResponse(ITEM_BROKER.toString(), List.of("B")))));

    assertThatThrownBy(() -> assessments.insertResponse(
        attemptId, ITEM_TOPIC, "{\"selectedOptions\":[\"C\"]}", true))
        .isInstanceOf(DataAccessException.class);
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
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
}
