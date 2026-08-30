package io.ramals.learningplatform.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.AssessmentRepository;
import io.ramals.learningplatform.assessment.DiagnosticScorer;
import io.ramals.learningplatform.assessment.DiagnosticService;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionService;
import io.ramals.learningplatform.learner.Learner;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
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
 * Verifies the evidence ledger is immutable at both the PostgreSQL privilege layer (runtime holds
 * only SELECT+INSERT) and via the append-only trigger (owner UPDATE/DELETE rejected), that source
 * lineage makes appends idempotent, that adjustments supersede without rewriting, and that
 * submission records evidence with interactionId provenance exactly once under retry.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class EvidenceLedgerPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID VERSION = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID ITEM_BROKER = UUID.fromString("01900000-0000-7000-8000-000000000411");
  private static final UUID ITEM_TOPIC = UUID.fromString("01900000-0000-7000-8000-000000000412");
  private static final String INTERACTION_ID = "01920000-0000-7000-8000-0000000000e1";

  private static String databaseUrl;
  private AssessmentRepository assessments;
  private LearnerRepository learners;
  private EvidenceRepository evidence;
  private EvidenceService evidenceService;
  private DiagnosticService diagnostics;
  private DiagnosticSubmissionService submissions;
  private TransactionTemplate transactionTemplate;
  private JdbcTemplate runtimeJdbc;
  private JdbcTemplate migrationJdbc;

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

    org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private void wire() {
    if (evidenceService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      migrationJdbc = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD));
      JsonMapper mapper = JsonMapper.builder().build();
      assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      evidence = new EvidenceRepository(runtimeJdbc);
      evidenceService = new EvidenceService(evidence);
      LearnerService learnerService = new LearnerService(learners);
      diagnostics = new DiagnosticService(assessments, learnerService);
      MasteryService masteryService = new MasteryService(
          new MasteryRepository(runtimeJdbc), evidence, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculator(), new MasteryStatusPolicy());
      RecommendationService recommendationService = new RecommendationService(
          new RecommendationPolicy(), new RecommendationRepository(runtimeJdbc), learnerService);
      submissions = new DiagnosticSubmissionService(
          assessments, learnerService, new DiagnosticScorer(), evidenceService, masteryService,
          recommendationService, mapper);
      transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }
  }

  private UUID provisionLearner(String subject) {
    return learners.provisionForSubject(subject).id();
  }

  private UUID createAttempt(String subject) {
    return diagnostics.createAttempt(subject, "kafka", "key-1").attempt().id();
  }

  private Evidence appendBaseEvidence(UUID learnerId, UUID attemptId, String lineageKey) {
    return evidence.appendDiagnosticEvidence(
        learnerId, BROKER_SKILL, attemptId, VERSION, DiagnosticScorer.SCORING_VERSION, lineageKey,
        new BigDecimal("1.0000"), new BigDecimal("1.0000"), 1, 1, INTERACTION_ID);
  }

  @Test
  void submissionRecordsImmutableEvidenceWithProvenanceExactlyOnce() {
    wire();
    UUID attemptId = createAttempt("ev-e2e");
    Learner learner = learners.findBySubject("ev-e2e").orElseThrow();

    MDC.put("interactionId", INTERACTION_ID);
    try {
      DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(List.of(
          new ItemResponse(ITEM_BROKER.toString(), List.of("B")),
          new ItemResponse(ITEM_TOPIC.toString(), List.of("A"))));
      transactionTemplate.execute(status ->
          submissions.submit("ev-e2e", "kafka", attemptId.toString(), request));
      transactionTemplate.execute(status ->  // retry on the completed attempt
          submissions.submit("ev-e2e", "kafka", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }

    List<Evidence> brokerEvidence = evidence.findByLearnerAndSkill(learner.id(), BROKER_SKILL);
    assertThat(brokerEvidence).hasSize(1);
    Evidence recorded = brokerEvidence.getFirst();
    assertThat(recorded.evidenceType()).isEqualTo("DIAGNOSTIC");
    assertThat(recorded.normalizedScore()).isEqualByComparingTo("1.0000");
    assertThat(recorded.interactionId()).isEqualTo(INTERACTION_ID);
    assertThat(recorded.scoringVersion()).isEqualTo(DiagnosticScorer.SCORING_VERSION);
    assertThat(recorded.sourceAttemptId()).isEqualTo(attemptId);

    Long total = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM ledger.evidence WHERE source_attempt_id = ?", Long.class, attemptId);
    assertThat(total).isEqualTo(2);  // one per answered skill, not duplicated by the retry
  }

  @Test
  void duplicateLineageReusesEvidence() {
    wire();
    UUID learnerId = provisionLearner("ev-lineage");
    UUID attemptId = createAttempt("ev-lineage");
    String lineage = "TEST_LINEAGE:reuse";

    Evidence first = appendBaseEvidence(learnerId, attemptId, lineage);
    Evidence retry = appendBaseEvidence(learnerId, attemptId, lineage);

    assertThat(retry.id()).isEqualTo(first.id());
    Long rows = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM ledger.evidence WHERE lineage_key = ?", Long.class, lineage);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void runtimeRoleCannotUpdateOrDeleteEvidence() {
    wire();
    UUID learnerId = provisionLearner("ev-runtime");
    UUID attemptId = createAttempt("ev-runtime");
    Evidence row = appendBaseEvidence(learnerId, attemptId, "TEST_LINEAGE:runtime");

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE ledger.evidence SET normalized_score = 0.5000 WHERE id = ?", row.id()))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM ledger.evidence WHERE id = ?", row.id()))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
  }

  @Test
  void appendOnlyTriggerBlocksOwnerMutation() {
    wire();
    UUID learnerId = provisionLearner("ev-owner");
    UUID attemptId = createAttempt("ev-owner");
    Evidence row = appendBaseEvidence(learnerId, attemptId, "TEST_LINEAGE:owner");

    assertThatThrownBy(() -> migrationJdbc.update(
        "UPDATE ledger.evidence SET normalized_score = 0.5000 WHERE id = ?", row.id()))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
    assertThatThrownBy(() -> migrationJdbc.update(
        "DELETE FROM ledger.evidence WHERE id = ?", row.id()))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
  }

  @Test
  void adjustmentEvidenceSupersedesWithoutRewriting() {
    wire();
    UUID learnerId = provisionLearner("ev-adjust");
    UUID attemptId = createAttempt("ev-adjust");
    Evidence original = appendBaseEvidence(learnerId, attemptId, "TEST_LINEAGE:adjust");

    Evidence adjustment = evidenceService.appendAdjustment(
        original.id(), new BigDecimal("0.0000"), new BigDecimal("0.0000"), "reason-1", INTERACTION_ID);
    Evidence retry = evidenceService.appendAdjustment(
        original.id(), new BigDecimal("0.0000"), new BigDecimal("0.0000"), "reason-1", INTERACTION_ID);

    assertThat(adjustment.evidenceType()).isEqualTo("ADJUSTMENT");
    assertThat(adjustment.adjustsEvidenceId()).isEqualTo(original.id());
    assertThat(retry.id()).isEqualTo(adjustment.id());  // idempotent per (original, reason)

    Evidence originalReloaded = evidence.findById(original.id()).orElseThrow();
    assertThat(originalReloaded.normalizedScore()).isEqualByComparingTo("1.0000");  // untouched
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
