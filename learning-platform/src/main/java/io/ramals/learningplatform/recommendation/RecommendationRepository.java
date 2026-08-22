package io.ramals.learningplatform.recommendation;

import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.observability.UuidV7;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RecommendationRepository {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbcTemplate;

  public RecommendationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Appends an immutable decision record for a snapshot, reusing any existing record for that
   * snapshot. Idempotent per snapshot, so a repeated recompute cannot duplicate a decision.
   */
  public DecisionRecord appendDecisionRecord(
      MasterySnapshot snapshot, RecommendationDecision decision, String policyVersion,
      String interactionId, String traceId) {
    jdbcTemplate.update("""
        INSERT INTO ledger.decision_record
          (id, learner_id, skill_id, curriculum_version_id, decision_type, recommended_action,
           reason_code, mastery_status, policy_decision, source_snapshot_id, aggregate_version,
           mastery_score, evidence_confidence, mastery_threshold, confidence_threshold,
           evidence_count, items_considered, mastery_algorithm_version, confidence_algorithm_version,
           policy_version, interaction_id, trace_id)
        VALUES (?, ?, ?, ?, 'RECOMMENDATION', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (source_snapshot_id) DO NOTHING
        """,
        UuidV7.generate(), snapshot.learnerId(), snapshot.skillId(), snapshot.curriculumVersionId(),
        decision.action().name(), decision.reasonCode(), snapshot.status().name(),
        decision.action().name(), snapshot.id(), snapshot.aggregateVersion(), snapshot.masteryScore(),
        snapshot.evidenceConfidence(), snapshot.threshold(), snapshot.confidenceThreshold(),
        snapshot.evidenceCount(), snapshot.itemsConsidered(), snapshot.algorithmVersion(),
        snapshot.confidenceAlgorithmVersion(), policyVersion, interactionId,
        traceId == null || traceId.isBlank() ? null : traceId);
    return findDecisionBySnapshot(snapshot.id()).orElseThrow(
        () -> new IllegalStateException("Decision record did not persist."));
  }

  /** Appends the current-surface recommendation for a decision. Idempotent per decision record. */
  public LearningRecommendation appendRecommendation(
      MasterySnapshot snapshot, RecommendationDecision decision, UUID decisionRecordId) {
    jdbcTemplate.update("""
        INSERT INTO core.learning_recommendation
          (id, learner_id, skill_id, curriculum_version_id, recommended_action, reason_code,
           mastery_status, decision_record_id, source_snapshot_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (decision_record_id) DO NOTHING
        """,
        UuidV7.generate(), snapshot.learnerId(), snapshot.skillId(), snapshot.curriculumVersionId(),
        decision.action().name(), decision.reasonCode(), snapshot.status().name(),
        decisionRecordId, snapshot.id());
    return findRecommendationByDecision(decisionRecordId).orElseThrow(
        () -> new IllegalStateException("Learning recommendation did not persist."));
  }

  /**
   * Enqueues one durable adaptation comparison for an authoritative decision.
   *
   * <p>This method participates in the caller's transaction. The decision, learner-facing
   * recommendation, and work item therefore commit or roll back together. A deterministic request
   * ID and source uniqueness constraint make a repeated recompute return the existing work rather
   * than create another logical dispatch.
   */
  public UUID appendAdaptationWork(DecisionRecord decision) {
    UUID workId = UuidV7.generate();
    String requestId = deterministicId("ADAPTATION|" + decision.id());
    String groundedContextId = deterministicId("GROUNDED_CONTEXT|" + decision.id());
    String traceId = decision.traceId() == null || decision.traceId().isBlank()
        ? decision.interactionId()
        : decision.traceId();
    Instant createdAt = Instant.now();
    AgentWorkPayload payload = new AgentWorkPayload(
        "1.0", workId.toString(), requestId, decision.interactionId(), traceId, "ADAPTATION",
        "ADAPT", decision.id().toString(), groundedContextId, createdAt.toString());

    jdbcTemplate.update("""
        INSERT INTO core.agent_work_outbox
          (id, request_id, interaction_id, trace_id, agent_type, capability, source_decision_id,
           grounded_context_id, payload_version, payload, status, attempt_count, next_attempt_at,
           created_at)
        VALUES (?, ?, ?, ?, 'ADAPTATION', 'ADAPT', ?, ?, 1, CAST(? AS jsonb), 'PENDING', 0, ?, ?)
        ON CONFLICT (request_id) DO NOTHING
        """, workId, requestId, decision.interactionId(), traceId, decision.id(), groundedContextId,
        JSON.writeValueAsString(payload), java.sql.Timestamp.from(createdAt),
        java.sql.Timestamp.from(createdAt));

    return jdbcTemplate.queryForObject(
        "SELECT id FROM core.agent_work_outbox WHERE request_id = ?", UUID.class, requestId);
  }

  public Optional<DecisionRecord> findDecisionBySnapshot(UUID snapshotId) {
    return jdbcTemplate.query(DECISION_SELECT + " WHERE source_snapshot_id = ?", DECISION_MAPPER,
        snapshotId).stream().findFirst();
  }

  public Optional<DecisionRecord> findDecisionById(UUID id) {
    return jdbcTemplate.query(DECISION_SELECT + " WHERE id = ?", DECISION_MAPPER, id)
        .stream().findFirst();
  }

  public List<DecisionRecord> findDecisionsByInteractionId(String interactionId) {
    return jdbcTemplate.query(DECISION_SELECT + " WHERE interaction_id = ? ORDER BY decided_at, id",
        DECISION_MAPPER, interactionId);
  }

  public List<LearningRecommendation> findCurrentByLearner(UUID learnerId) {
    return jdbcTemplate.query("""
        SELECT DISTINCT ON (lr.skill_id) lr.id, lr.learner_id, lr.skill_id, s.stable_code AS skill_code,
               lr.curriculum_version_id, lr.recommended_action, lr.reason_code, lr.mastery_status,
               lr.decision_record_id, lr.source_snapshot_id, lr.created_at
        FROM core.learning_recommendation lr
        JOIN core.skill s ON s.id = lr.skill_id
        WHERE lr.learner_id = ?
        ORDER BY lr.skill_id, lr.created_at DESC, lr.id DESC
        """, RECOMMENDATION_MAPPER, learnerId);
  }

  private Optional<LearningRecommendation> findRecommendationByDecision(UUID decisionRecordId) {
    return jdbcTemplate.query("""
        SELECT lr.id, lr.learner_id, lr.skill_id, s.stable_code AS skill_code,
               lr.curriculum_version_id, lr.recommended_action, lr.reason_code, lr.mastery_status,
               lr.decision_record_id, lr.source_snapshot_id, lr.created_at
        FROM core.learning_recommendation lr
        JOIN core.skill s ON s.id = lr.skill_id
        WHERE lr.decision_record_id = ?
        """, RECOMMENDATION_MAPPER, decisionRecordId).stream().findFirst();
  }

  private static final String DECISION_SELECT = """
      SELECT id, learner_id, skill_id, curriculum_version_id, decision_type, recommended_action,
             reason_code, mastery_status, source_snapshot_id, aggregate_version, mastery_score,
             evidence_confidence, mastery_threshold, confidence_threshold, evidence_count,
             items_considered, mastery_algorithm_version, confidence_algorithm_version,
             policy_version, interaction_id, trace_id, decided_at
      FROM ledger.decision_record
      """;

  private static final RowMapper<DecisionRecord> DECISION_MAPPER = (result, row) -> new DecisionRecord(
      result.getObject("id", UUID.class),
      result.getObject("learner_id", UUID.class),
      result.getObject("skill_id", UUID.class),
      result.getObject("curriculum_version_id", UUID.class),
      result.getString("decision_type"),
      RecommendedAction.valueOf(result.getString("recommended_action")),
      result.getString("reason_code"),
      result.getString("mastery_status"),
      result.getObject("source_snapshot_id", UUID.class),
      result.getInt("aggregate_version"),
      result.getBigDecimal("mastery_score"),
      result.getBigDecimal("evidence_confidence"),
      result.getBigDecimal("mastery_threshold"),
      result.getBigDecimal("confidence_threshold"),
      result.getInt("evidence_count"),
      result.getInt("items_considered"),
      result.getString("mastery_algorithm_version"),
      result.getString("confidence_algorithm_version"),
      result.getString("policy_version"),
      result.getString("interaction_id"),
      result.getString("trace_id"),
      instant(result, "decided_at"));

  private static final RowMapper<LearningRecommendation> RECOMMENDATION_MAPPER =
      (result, row) -> new LearningRecommendation(
          result.getObject("id", UUID.class),
          result.getObject("learner_id", UUID.class),
          result.getObject("skill_id", UUID.class),
          result.getString("skill_code"),
          result.getObject("curriculum_version_id", UUID.class),
          RecommendedAction.valueOf(result.getString("recommended_action")),
          result.getString("reason_code"),
          result.getString("mastery_status"),
          result.getObject("decision_record_id", UUID.class),
          result.getObject("source_snapshot_id", UUID.class),
          instant(result, "created_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static String deterministicId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private record AgentWorkPayload(
      String contractVersion,
      String workId,
      String requestId,
      String interactionId,
      String traceId,
      String agentType,
      String capability,
      String sourceDecisionId,
      String groundedContextId,
      String createdAt) {
  }
}
