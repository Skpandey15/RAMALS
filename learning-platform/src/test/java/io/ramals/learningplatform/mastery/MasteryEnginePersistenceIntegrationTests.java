package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.AssessmentRepository;
import io.ramals.learningplatform.assessment.DiagnosticScorer;
import io.ramals.learningplatform.assessment.DiagnosticService;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionService;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the deterministic mastery engine against real PostgreSQL: no evidence yields
 * INSUFFICIENT_EVIDENCE, concurrent recomputes serialize into monotonic versions with one snapshot
 * each, a version can never carry two snapshots, submission drives a snapshot, and the same evidence
 * reproduces the identical persisted score.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class MasteryEnginePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID CURRICULUM_VERSION = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID ASSESSMENT_VERSION = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID TOPIC_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000102");
  private static final UUID ITEM_BROKER = UUID.fromString("01900000-0000-7000-8000-000000000411");
  private static final String INTERACTION_ID = "01920000-0000-7000-8000-0000000000e1";

  private static String databaseUrl;
  private LearnerRepository learners;
  private EvidenceRepository evidence;
  private MasteryRepository masteryRepository;
  private MasteryService masteryService;
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

    org.flywaydb.core.Flyway.configure()
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
    if (masteryService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      learners = new LearnerRepository(runtimeJdbc);
      evidence = new EvidenceRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      masteryService = new MasteryService(
          masteryRepository, evidence, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculator(), new MasteryStatusPolicy());
      AssessmentRepository assessments = new AssessmentRepository(runtimeJdbc, mapper);
      LearnerService learnerService = new LearnerService(learners);
      diagnostics = new DiagnosticService(assessments, learnerService);
      submissions = new DiagnosticSubmissionService(
          assessments, learnerService, new DiagnosticScorer(),
          new EvidenceService(evidence), masteryService, mapper);
      transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }
  }

  private MasterySnapshot recomputeInTx(UUID learnerId, UUID skillId) {
    return transactionTemplate.execute(status ->
        masteryService.recompute(learnerId, skillId, CURRICULUM_VERSION, INTERACTION_ID));
  }

  private int aggregateVersion(UUID learnerId, UUID skillId) {
    return runtimeJdbc.queryForObject("""
        SELECT aggregate_version FROM core.learner_skill_aggregate
        WHERE learner_id = ? AND skill_id = ? AND curriculum_version_id = ?
        """, Integer.class, learnerId, skillId, CURRICULUM_VERSION);
  }

  @Test
  void noEvidenceRecomputeIsInsufficientEvidence() {
    wire();
    UUID learnerId = learners.provisionForSubject("m-none").id();

    MasterySnapshot snapshot = recomputeInTx(learnerId, BROKER_SKILL);

    assertThat(snapshot.aggregateVersion()).isEqualTo(1);
    assertThat(snapshot.status()).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
    assertThat(snapshot.masteryScore()).isEqualByComparingTo("0.0000");
    assertThat(snapshot.algorithmVersion()).isEqualTo(WeightedMasteryCalculator.ALGORITHM_VERSION);
  }

  @Test
  void concurrentRecomputesProduceMonotonicVersions() throws InterruptedException {
    wire();
    UUID learnerId = learners.provisionForSubject("m-concurrent").id();
    masteryRepository.ensureAggregate(learnerId, BROKER_SKILL, CURRICULUM_VERSION);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Runnable recompute = () -> {
      try {
        start.await();
        recomputeInTx(learnerId, BROKER_SKILL);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    };
    pool.submit(recompute);
    pool.submit(recompute);
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(aggregateVersion(learnerId, BROKER_SKILL)).isEqualTo(2);
    assertThat(masteryRepository.findSnapshots(learnerId, BROKER_SKILL, CURRICULUM_VERSION))
        .extracting(MasterySnapshot::aggregateVersion)
        .containsExactly(1, 2);
  }

  @Test
  void aVersionCannotCarryTwoSnapshots() {
    wire();
    UUID learnerId = learners.provisionForSubject("m-duplicate").id();
    recomputeInTx(learnerId, BROKER_SKILL);  // creates version 1

    MasterySnapshotDraft draft = new MasterySnapshotDraft(
        learnerId, BROKER_SKILL, CURRICULUM_VERSION, 1, new BigDecimal("0.5000"),
        MasteryStatus.NEEDS_PRACTICE, new BigDecimal("0.8000"), new BigDecimal("0.3300"),
        new BigDecimal("0.7500"), 1, 1, WeightedMasteryCalculator.ALGORITHM_VERSION,
        EvidenceConfidenceCalculator.ALGORITHM_VERSION, INTERACTION_ID);
    assertThatThrownBy(() -> transactionTemplate.execute(
        status -> masteryRepository.insertSnapshot(draft)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void submissionDrivesMasterySnapshots() {
    wire();
    UUID attemptId = diagnostics.createAttempt("m-submit", "kafka", "key-1").attempt().id();
    UUID learnerId = learners.findBySubject("m-submit").orElseThrow().id();

    MDC.put("interactionId", INTERACTION_ID);
    try {
      DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(List.of(
          new ItemResponse(ITEM_BROKER.toString(), List.of("B"))));
      transactionTemplate.execute(status ->
          submissions.submit("m-submit", "kafka", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }

    MasterySnapshot broker = masteryService
        .latestSnapshot(learnerId, BROKER_SKILL, CURRICULUM_VERSION).orElseThrow();
    assertThat(broker.aggregateVersion()).isEqualTo(1);
    assertThat(broker.masteryScore()).isEqualByComparingTo("1.0000");
    // one diagnostic item is below the required evidence volume, so the skill stays insufficient
    assertThat(broker.status()).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
    assertThat(broker.interactionId()).isEqualTo(INTERACTION_ID);
    // sparse diagnostic: 0.40*0.20 + 0.35*0 + 0.15*1 + 0.10*1 = 0.3300, below the confidence threshold
    assertThat(broker.evidenceConfidence()).isEqualByComparingTo("0.3300");
    assertThat(broker.confidenceThreshold()).isEqualByComparingTo("0.7500");
    assertThat(broker.confidenceAlgorithmVersion())
        .isEqualTo(EvidenceConfidenceCalculator.ALGORITHM_VERSION);
  }

  @Test
  void repeatedRecomputeReproducesIdenticalScore() {
    wire();
    UUID learnerId = learners.provisionForSubject("m-repro").id();
    UUID attemptId = diagnostics.createAttempt("m-repro", "kafka", "key-1").attempt().id();
    evidence.appendDiagnosticEvidence(
        learnerId, BROKER_SKILL, attemptId, ASSESSMENT_VERSION, DiagnosticScorer.SCORING_VERSION,
        "REPRO:1", new BigDecimal("0.6000"), new BigDecimal("0.6000"), 5, 3, INTERACTION_ID);

    MasterySnapshot first = recomputeInTx(learnerId, BROKER_SKILL);
    MasterySnapshot second = recomputeInTx(learnerId, BROKER_SKILL);

    assertThat(first.aggregateVersion()).isEqualTo(1);
    assertThat(second.aggregateVersion()).isEqualTo(2);
    assertThat(second.masteryScore()).isEqualByComparingTo(first.masteryScore());
    assertThat(first.masteryScore()).isEqualByComparingTo("0.6000");
    assertThat(first.status()).isEqualTo(second.status());
    assertThat(first.status()).isEqualTo(MasteryStatus.NEEDS_PRACTICE);
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
