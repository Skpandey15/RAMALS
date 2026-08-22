package io.ramals.learningplatform.recommendation;

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
import io.ramals.learningplatform.execution.AgentWorkOutboxRepository;
import io.ramals.learningplatform.execution.ClaimedAgentWork;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.EvidenceConfidenceCalculator;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryStatusPolicy;
import io.ramals.learningplatform.mastery.WeightedMasteryCalculator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Verifies recommendation provenance against real PostgreSQL: submission drives a recommendation and
 * an immutable decision record, the decision is reconstructable from its own denormalized snapshot
 * inputs, decisions cannot be updated or deleted (privilege and trigger layers), decisions are
 * searchable by interactionId, and a repeated recompute never duplicates a decision.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class RecommendationPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID CURRICULUM_VERSION = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID ASSESSMENT_VERSION = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ITEM_BROKER = UUID.fromString("01900000-0000-7000-8000-000000000411");
  private static final String INTERACTION_ID = "01920000-0000-7000-8000-0000000000e1";

  private static String databaseUrl;
  private LearnerRepository learners;
  private EvidenceRepository evidence;
  private MasteryService masteryService;
  private RecommendationRepository recommendations;
  private RecommendationService recommendationService;
  private RecommendationPolicy policy;
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
    if (recommendationService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      migrationJdbc = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD));
      JsonMapper mapper = JsonMapper.builder().build();
      learners = new LearnerRepository(runtimeJdbc);
      evidence = new EvidenceRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      masteryService = new MasteryService(
          new MasteryRepository(runtimeJdbc), evidence, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculator(), new MasteryStatusPolicy());
      policy = new RecommendationPolicy();
      recommendations = new RecommendationRepository(runtimeJdbc);
      recommendationService = new RecommendationService(policy, recommendations, learnerService);
      AssessmentRepository assessments = new AssessmentRepository(runtimeJdbc, mapper);
      diagnostics = new DiagnosticService(assessments, learnerService);
      submissions = new DiagnosticSubmissionService(
          assessments, learnerService, new DiagnosticScorer(), new EvidenceService(evidence),
          masteryService, recommendationService, mapper);
      transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }
  }

  private MasterySnapshot recomputeWithEvidence(String subject, String normalized) {
    UUID learnerId = learners.provisionForSubject(subject).id();
    UUID attemptId = diagnostics.createAttempt(subject, "kafka", "key-1").attempt().id();
    evidence.appendDiagnosticEvidence(
        learnerId, BROKER_SKILL, attemptId, ASSESSMENT_VERSION, DiagnosticScorer.SCORING_VERSION,
        subject + ":evidence", new BigDecimal(normalized), new BigDecimal(normalized), 5, 3,
        INTERACTION_ID);
    return transactionTemplate.execute(status ->
        masteryService.recompute(learnerId, BROKER_SKILL, CURRICULUM_VERSION, INTERACTION_ID));
  }

  private LearningRecommendation recommendInTx(MasterySnapshot snapshot) {
    return transactionTemplate.execute(status ->
        recommendationService.recommend(snapshot, INTERACTION_ID, ""));
  }

  @Test
  void submissionProducesRecommendationAndDecisionRecord() {
    wire();
    UUID attemptId = diagnostics.createAttempt("rec-submit", "kafka", "key-1").attempt().id();
    UUID learnerId = learners.findBySubject("rec-submit").orElseThrow().id();

    MDC.put("interactionId", INTERACTION_ID);
    try {
      DiagnosticSubmissionRequest request = new DiagnosticSubmissionRequest(List.of(
          new ItemResponse(ITEM_BROKER.toString(), List.of("B"))));
      transactionTemplate.execute(status ->
          submissions.submit("rec-submit", "kafka", attemptId.toString(), request));
    } finally {
      MDC.remove("interactionId");
    }

    List<LearningRecommendation> current = recommendations.findCurrentByLearner(learnerId);
    LearningRecommendation broker = current.stream()
        .filter(recommendation -> recommendation.skillId().equals(BROKER_SKILL))
        .findFirst().orElseThrow();
    // sparse diagnostic -> INSUFFICIENT_EVIDENCE -> COLLECT_EVIDENCE
    assertThat(broker.recommendedAction()).isEqualTo(RecommendedAction.COLLECT_EVIDENCE);
    assertThat(broker.reasonCode()).isEqualTo("INSUFFICIENT_EVIDENCE");
    assertThat(recommendations.findDecisionById(broker.decisionRecordId())).isPresent();
  }

  @Test
  void decisionIsReconstructableFromItsSnapshotInputs() {
    wire();
    MasterySnapshot snapshot = recomputeWithEvidence("rec-reconstruct", "0.6000");
    LearningRecommendation recommendation = recommendInTx(snapshot);

    DecisionRecord decision = recommendations.findDecisionById(recommendation.decisionRecordId())
        .orElseThrow();
    assertThat(decision.sourceSnapshotId()).isEqualTo(snapshot.id());
    assertThat(decision.aggregateVersion()).isEqualTo(snapshot.aggregateVersion());
    assertThat(decision.masteryScore()).isEqualByComparingTo(snapshot.masteryScore());
    assertThat(decision.evidenceConfidence()).isEqualByComparingTo(snapshot.evidenceConfidence());
    assertThat(decision.masteryThreshold()).isEqualByComparingTo(snapshot.threshold());
    assertThat(decision.masteryAlgorithmVersion()).isEqualTo(snapshot.algorithmVersion());
    assertThat(decision.confidenceAlgorithmVersion()).isEqualTo(snapshot.confidenceAlgorithmVersion());
    assertThat(decision.policyVersion()).isEqualTo(RecommendationPolicy.POLICY_VERSION);
    // the decision replays deterministically from the snapshot it cites
    assertThat(decision.recommendedAction()).isEqualTo(policy.decide(snapshot).action());
  }

  @Test
  void decisionRecordsCannotBeUpdatedOrDeleted() {
    wire();
    MasterySnapshot snapshot = recomputeWithEvidence("rec-immutable", "0.6000");
    DecisionRecord decision = recommendations.findDecisionById(
        recommendInTx(snapshot).decisionRecordId()).orElseThrow();

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE ledger.decision_record SET reason_code = 'X' WHERE id = ?", decision.id()))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
    assertThatThrownBy(() -> migrationJdbc.update(
        "DELETE FROM ledger.decision_record WHERE id = ?", decision.id()))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
  }

  @Test
  void decisionsAreSearchableByInteractionId() {
    wire();
    MasterySnapshot snapshot = recomputeWithEvidence("rec-interaction", "0.6000");
    LearningRecommendation recommendation = recommendInTx(snapshot);

    assertThat(recommendations.findDecisionsByInteractionId(INTERACTION_ID))
        .extracting(DecisionRecord::id)
        .contains(recommendation.decisionRecordId());
  }

  @Test
  void repeatedRecommendIsIdempotentPerSnapshot() {
    wire();
    MasterySnapshot snapshot = recomputeWithEvidence("rec-idempotent", "0.6000");

    LearningRecommendation first = recommendInTx(snapshot);
    LearningRecommendation second = recommendInTx(snapshot);
    assertThat(second.decisionRecordId()).isEqualTo(first.decisionRecordId());

    Long decisions = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM ledger.decision_record WHERE source_snapshot_id = ?",
        Long.class, snapshot.id());
    assertThat(decisions).isEqualTo(1);

    Long workItems = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.agent_work_outbox WHERE source_decision_id = ?",
        Long.class, first.decisionRecordId());
    assertThat(workItems).isEqualTo(1);
  }

  @Test
  void recommendationAndAgentWorkCommitAtomicallyWithContractPayload() {
    wire();
    MasterySnapshot snapshot = recomputeWithEvidence("rec-outbox-atomic", "0.6000");
    LearningRecommendation recommendation = recommendInTx(snapshot);

    var work = runtimeJdbc.queryForMap("""
        SELECT request_id, interaction_id, trace_id, agent_type, capability, source_decision_id,
               payload_version, status, attempt_count, payload->>'workId' AS payload_work_id,
               payload->>'sourceDecisionId' AS payload_decision_id,
               payload->>'groundedContextId' AS grounded_context_id
          FROM core.agent_work_outbox
         WHERE source_decision_id = ?
        """, recommendation.decisionRecordId());

    assertThat(work.get("interaction_id")).isEqualTo(INTERACTION_ID);
    // The existing caller has no W3C trace in this fixture. The durable work uses the interaction
    // identity as its non-empty compatibility correlation until the boundary always supplies one.
    assertThat(work.get("trace_id")).isEqualTo(INTERACTION_ID);
    assertThat(work.get("agent_type")).isEqualTo("ADAPTATION");
    assertThat(work.get("capability")).isEqualTo("ADAPT");
    assertThat(work.get("payload_version")).isEqualTo(1);
    assertThat(work.get("status")).isEqualTo("PENDING");
    assertThat(work.get("attempt_count")).isEqualTo(0);
    assertThat(work.get("payload_decision_id"))
        .isEqualTo(recommendation.decisionRecordId().toString());
    assertThat(work.get("payload_work_id")).isNotNull();
    assertThat(work.get("grounded_context_id")).isNotNull();
  }

  @Test
  void rollbackRemovesDecisionRecommendationAndAgentWorkTogether() {
    wire();
    MasterySnapshot snapshot = recomputeWithEvidence("rec-outbox-rollback", "0.6000");

    transactionTemplate.executeWithoutResult(status -> {
      recommendationService.recommend(snapshot, INTERACTION_ID, "");
      status.setRollbackOnly();
    });

    Long decisions = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM ledger.decision_record WHERE source_snapshot_id = ?",
        Long.class, snapshot.id());
    Long recommendations = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.learning_recommendation WHERE source_snapshot_id = ?",
        Long.class, snapshot.id());
    Long workItems = runtimeJdbc.queryForObject("""
        SELECT count(*)
          FROM core.agent_work_outbox work
          JOIN ledger.decision_record decision ON decision.id = work.source_decision_id
         WHERE decision.source_snapshot_id = ?
        """, Long.class, snapshot.id());

    assertThat(decisions).isZero();
    assertThat(recommendations).isZero();
    assertThat(workItems).isZero();
  }

  @Test
  void outboxIdentityAndPayloadCannotBeRewritten() {
    wire();
    MasterySnapshot snapshot = recomputeWithEvidence("rec-outbox-immutable", "0.6000");
    LearningRecommendation recommendation = recommendInTx(snapshot);

    assertThatThrownBy(() -> runtimeJdbc.update("""
        UPDATE core.agent_work_outbox
           SET payload = jsonb_set(payload, '{capability}', '"TAMPERED"')
         WHERE source_decision_id = ?
        """, recommendation.decisionRecordId()))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
  }

  @Test
  void concurrentDispatchersClaimDifferentRows() throws Exception {
    wire();
    completeOutstandingWork();
    recommendInTx(recomputeWithEvidence("rec-claim-a", "0.6000"));
    recommendInTx(recomputeWithEvidence("rec-claim-b", "0.6000"));
    AgentWorkOutboxRepository outbox = new AgentWorkOutboxRepository(runtimeJdbc);

    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> outbox.claim("dispatcher-a", 1, 60_000));
      var second = workers.submit(() -> outbox.claim("dispatcher-b", 1, 60_000));
      List<ClaimedAgentWork> claimed = java.util.stream.Stream.concat(
          first.get(10, TimeUnit.SECONDS).stream(), second.get(10, TimeUnit.SECONDS).stream())
          .toList();

      assertThat(claimed).hasSize(2);
      assertThat(claimed).extracting(ClaimedAgentWork::id).doesNotHaveDuplicates();
      assertThat(claimed).extracting(ClaimedAgentWork::leaseOwner)
          .containsExactlyInAnyOrder("dispatcher-a", "dispatcher-b");
    }
  }

  @Test
  void expiredLeaseIsRecoveredAndStaleOwnerCannotComplete() {
    wire();
    completeOutstandingWork();
    LearningRecommendation recommendation = recommendInTx(
        recomputeWithEvidence("rec-lease-recovery", "0.6000"));
    AgentWorkOutboxRepository outbox = new AgentWorkOutboxRepository(runtimeJdbc);
    ClaimedAgentWork first = outbox.claim("dispatcher-a", 1, 60_000).getFirst();
    runtimeJdbc.update("""
        UPDATE core.agent_work_outbox
           SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
         WHERE id = ?
        """, first.id());

    ClaimedAgentWork recovered = outbox.claim("dispatcher-b", 1, 60_000).getFirst();

    assertThat(recovered.id()).isEqualTo(first.id());
    assertThat(recovered.sourceDecisionId()).isEqualTo(recommendation.decisionRecordId());
    assertThat(recovered.attemptCount()).isEqualTo(2);
    assertThatThrownBy(() -> outbox.complete(first))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease conflict");
    outbox.complete(recovered);
  }

  @Test
  void terminalWorkRequiresExplicitReplayAndPreservesLifetimeAccounting() {
    wire();
    completeOutstandingWork();
    recommendInTx(recomputeWithEvidence("rec-explicit-replay", "0.6000"));
    AgentWorkOutboxRepository outbox = new AgentWorkOutboxRepository(runtimeJdbc);
    ClaimedAgentWork claimed = outbox.claim("dispatcher-a", 1, 60_000).getFirst();
    outbox.terminal(claimed, "AI_DEADLINE_EXCEEDED");

    assertThat(outbox.claim("dispatcher-b", 1, 60_000)).isEmpty();
    outbox.replayTerminal(claimed.id());
    ClaimedAgentWork replayed = outbox.claim("dispatcher-b", 1, 60_000).getFirst();
    var accounting = runtimeJdbc.queryForMap("""
        SELECT replay_count, total_attempt_count, terminal_reason
          FROM core.agent_work_outbox WHERE id = ?
        """, claimed.id());

    assertThat(replayed.attemptCount()).isEqualTo(1);
    assertThat(accounting.get("replay_count")).isEqualTo(1);
    assertThat(accounting.get("total_attempt_count")).isEqualTo(2);
    assertThat(accounting.get("terminal_reason")).isNull();
  }

  private void completeOutstandingWork() {
    runtimeJdbc.update("""
        UPDATE core.agent_work_outbox
           SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
               lease_owner = NULL, lease_expires_at = NULL
         WHERE status IN ('PENDING', 'RETRY', 'CLAIMED')
        """);
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
