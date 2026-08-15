package io.ramals.learningplatform.recommendation;

import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.observability.UuidV7;
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

@Repository
public class RecommendationRepository {

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
}
