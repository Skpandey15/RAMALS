package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.AcceptedEvaluationDecision;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationDecisionRecord;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationTarget;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DimensionResult;
import io.ramals.learningplatform.observability.UuidV7;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL adapter for append-only M2-T12 decisions. */
@Repository
public class JdbcAssessmentEvaluationDecisionRepository
    implements AssessmentEvaluationDecisionPort {

  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private final JdbcTemplate jdbc;

  public JdbcAssessmentEvaluationDecisionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(EvaluationDecisionRecord record) {
    requireRecord(record);
    requireAuthoritativeTarget(record);
    String digest = digest(record);
    BigDecimal normalizedScore =
        record.decision().allowsAuthoritativeEffect()
            ? EvaluationRubricScorePolicy.normalizedScore(record.decision().dimensions())
            : null;
    String scorePolicyVersion =
        record.decision().allowsAuthoritativeEffect()
            ? EvaluationRubricScorePolicy.POLICY_VERSION
            : null;
    if (record.target() != null) {
      requireTargetMatchesAuthoritativeFacts(record);
    }
    List<String> reasons = record.decision().reasons().stream().map(Enum::name).toList();
    List<String> evidence = record.decision().referencedEvidenceIds().stream().sorted().toList();
    List<Map<String, Object>> dimensions =
        record.decision().dimensions().stream().map(this::dimensionJson).toList();

    jdbc.update(
        """
        INSERT INTO ledger.assessment_evaluation_decision
          (id, proposal_id, request_id, agent_run_id, ai_execution_id, context_id,
           answer_evidence_id, answer_version, rubric_version, outcome, reason_codes,
           referenced_evidence_ids, dimension_results, feedback, confidence,
           deterministic_check, deterministic_reason_code, parser_reason_code,
           policy_version, decision_digest, interaction_id, trace_id,
           learner_id, skill_id, curriculum_version_id, attempt_id, assessment_version_id,
           normalized_score, score_policy_version)
        SELECT ?, ?, ?, ?, execution.id, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
               CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
          FROM core.ai_execution execution
         WHERE execution.request_id = ?
           AND execution.agent_run_id = ?
           AND execution.agent_type = 'ASSESSMENT'
           AND execution.status = 'SUCCEEDED'
        ON CONFLICT DO NOTHING
        """,
        UuidV7.generate(),
        record.proposalId(),
        record.requestId(),
        record.agentRunId(),
        record.contextId(),
        record.answerEvidenceId(),
        record.answerVersion(),
        record.rubricVersion(),
        record.decision().outcome().name(),
        JSON.writeValueAsString(reasons),
        JSON.writeValueAsString(evidence),
        JSON.writeValueAsString(dimensions),
        record.decision().feedback(),
        record.decision().confidence(),
        record.decision().deterministicCheck().comparison().name(),
        record.decision().deterministicCheck().reasonCode(),
        record.parserReasonCode(),
        EvaluationProposalGate.POLICY_VERSION,
        digest,
        record.interactionId(),
        record.traceId(),
        record.target() == null ? null : record.target().learnerId(),
        record.target() == null ? null : record.target().skillId(),
        record.target() == null ? null : record.target().curriculumVersionId(),
        record.target() == null ? null : record.target().attemptId(),
        record.target() == null ? null : record.target().assessmentVersionId(),
        normalizedScore,
        scorePolicyVersion,
        record.requestId(),
        record.agentRunId());

    StoredDecision stored =
        jdbc.query(
                """
                SELECT decision_digest, outcome
                  FROM ledger.assessment_evaluation_decision
                 WHERE request_id = ?
                """,
                (result, row) ->
                    new StoredDecision(
                        result.getString("decision_digest"), result.getString("outcome")),
                record.requestId())
            .stream()
            .findFirst()
            .orElseGet(() -> conflictingProposalIdentity(record));
    if (!digest.equals(stored.digest())) {
      throw new AssessmentEvaluationReplayConflictException(
          "evaluation requestId was replayed with different decision content");
    }
    if (!record.decision().outcome().name().equals(stored.outcome())) {
      throw new AssessmentEvaluationReplayConflictException(
          "evaluation requestId was replayed with a different gate outcome");
    }
  }

  @Override
  public Optional<AcceptedEvaluationDecision> findAcceptedByRequestId(
      String requestId) {
    if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
      return Optional.empty();
    }
    return jdbc
        .query(
            """
            SELECT request_id, learner_id, skill_id, curriculum_version_id, attempt_id,
                   assessment_version_id, normalized_score, score_policy_version,
                   interaction_id, trace_id
              FROM ledger.assessment_evaluation_decision
             WHERE request_id = ? AND outcome = 'ACCEPTED'
             ORDER BY decided_at DESC, id DESC
             LIMIT 1
            """,
            JdbcAssessmentEvaluationDecisionRepository::acceptedDecision,
            requestId)
        .stream()
        .findFirst();
  }

  private Map<String, Object> dimensionJson(DimensionResult dimension) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("dimensionId", dimension.dimensionId());
    json.put("score", dimension.score());
    json.put("maxScore", dimension.maxScore());
    json.put("reason", dimension.reason());
    json.put("evidenceIds", dimension.evidenceIds().stream().sorted().toList());
    return json;
  }

  private String digest(EvaluationDecisionRecord record) {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("proposalId", record.proposalId());
    canonical.put("requestId", record.requestId());
    canonical.put("agentRunId", record.agentRunId());
    canonical.put("contextId", record.contextId());
    canonical.put("answerEvidenceId", record.answerEvidenceId());
    canonical.put("answerVersion", record.answerVersion());
    canonical.put("rubricVersion", record.rubricVersion());
    canonical.put("outcome", record.decision().outcome().name());
    canonical.put("reasons", record.decision().reasons().stream().map(Enum::name).sorted().toList());
    canonical.put("evidenceIds", record.decision().referencedEvidenceIds().stream().sorted().toList());
    canonical.put(
        "dimensions", record.decision().dimensions().stream().map(this::dimensionJson).toList());
    canonical.put("feedback", record.decision().feedback());
    canonical.put("confidence", record.decision().confidence());
    canonical.put(
        "deterministicCheck", record.decision().deterministicCheck().comparison().name());
    canonical.put(
        "deterministicReasonCode", record.decision().deterministicCheck().reasonCode());
    canonical.put("parserReasonCode", record.parserReasonCode());
    canonical.put("policyVersion", EvaluationProposalGate.POLICY_VERSION);
    if (record.target() != null) {
      canonical.put("learnerId", record.target().learnerId());
      canonical.put("skillId", record.target().skillId());
      canonical.put("curriculumVersionId", record.target().curriculumVersionId());
      canonical.put("attemptId", record.target().attemptId());
      canonical.put("assessmentVersionId", record.target().assessmentVersionId());
      if (record.decision().allowsAuthoritativeEffect()) {
        canonical.put(
            "normalizedScore",
            EvaluationRubricScorePolicy.normalizedScore(record.decision().dimensions()));
        canonical.put("scorePolicyVersion", EvaluationRubricScorePolicy.POLICY_VERSION);
      }
    }
    try {
      byte[] serialized = JSON.writeValueAsBytes(canonical);
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireRecord(EvaluationDecisionRecord record) {
    if (record == null
        || record.decision() == null
        || record.decision().deterministicCheck() == null
        || !bounded(record.proposalId())
        || !bounded(record.requestId())
        || !bounded(record.agentRunId())
        || !bounded(record.contextId())
        || !bounded(record.answerEvidenceId())
        || !bounded(record.answerVersion())
        || !bounded(record.rubricVersion())
        || !bounded(record.interactionId())
        || !nullableBounded(record.traceId())) {
      throw new IllegalArgumentException("a complete, bounded evaluation decision is required");
    }
  }

  private static void requireAuthoritativeTarget(EvaluationDecisionRecord record) {
    if (record.target() != null && !record.target().complete()) {
      throw new IllegalArgumentException("evaluation target facts must be complete");
    }
    if (record.decision().allowsAuthoritativeEffect()
        && (record.target() == null || !record.target().complete())) {
      throw new IllegalArgumentException(
          "an accepted evaluation decision requires complete target facts");
    }
  }

  private void requireTargetMatchesAuthoritativeFacts(EvaluationDecisionRecord record) {
    EvaluationTarget target = record.target();
    Integer matches =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM ledger.grounding_retrieval_record grounding
              JOIN core.assessment_attempt attempt
                ON attempt.id = ?
               AND attempt.learner_id = ?
               AND attempt.assessment_version_id = ?
              JOIN core.assessment_version assessment_version
                ON assessment_version.id = ?
               AND assessment_version.curriculum_version_id = ?
              JOIN core.assessment_item_version assessment_item
                ON assessment_item.assessment_version_id = ?
               AND assessment_item.skill_id = ?
              JOIN core.skill_version skill_version
                ON skill_version.skill_id = ?
               AND skill_version.curriculum_version_id = ?
             WHERE grounding.context_id = ?
               AND grounding.learner_id = ?
            """,
            Integer.class,
            target.attemptId(),
            target.learnerId(),
            target.assessmentVersionId(),
            target.assessmentVersionId(),
            target.curriculumVersionId(),
            target.assessmentVersionId(),
            target.skillId(),
            target.skillId(),
            target.curriculumVersionId(),
            record.contextId(),
            target.learnerId());
    if (matches == null || matches != 1) {
      throw new IllegalArgumentException(
          "evaluation target does not match authoritative assessment facts");
    }
  }

  private static AcceptedEvaluationDecision acceptedDecision(ResultSet result, int row)
      throws SQLException {
    UUID learnerId = result.getObject("learner_id", UUID.class);
    UUID skillId = result.getObject("skill_id", UUID.class);
    UUID curriculumVersionId = result.getObject("curriculum_version_id", UUID.class);
    UUID attemptId = result.getObject("attempt_id", UUID.class);
    UUID assessmentVersionId = result.getObject("assessment_version_id", UUID.class);
    EvaluationTarget target =
        learnerId == null
                && skillId == null
                && curriculumVersionId == null
                && attemptId == null
                && assessmentVersionId == null
            ? null
            : new EvaluationTarget(
                learnerId,
                skillId,
                curriculumVersionId,
                attemptId,
                assessmentVersionId);
    return new AcceptedEvaluationDecision(
        result.getString("request_id"),
        target,
        result.getBigDecimal("normalized_score"),
        result.getString("score_policy_version"),
        result.getString("interaction_id"),
        result.getString("trace_id"));
  }

  private StoredDecision conflictingProposalIdentity(EvaluationDecisionRecord record) {
    boolean proposalIdentityExists =
        Boolean.TRUE.equals(
            jdbc.query(
                    """
                    SELECT true
                      FROM ledger.assessment_evaluation_decision
                     WHERE proposal_id = ? AND policy_version = ?
                    """,
                    (result, row) -> true,
                    record.proposalId(),
                    EvaluationProposalGate.POLICY_VERSION)
                .stream()
                .findFirst()
                .orElse(false));
    if (proposalIdentityExists) {
      throw new AssessmentEvaluationReplayConflictException(
          "evaluation proposal identity was reused for a different request");
    }
    throw new IllegalStateException(
        "evaluation decision requires a matching successful ASSESSMENT ai_execution");
  }

  private static boolean bounded(String value) {
    return value != null && !value.isBlank() && value.length() <= 64;
  }

  private static boolean nullableBounded(String value) {
    return value == null || bounded(value);
  }

  private record StoredDecision(String digest, String outcome) {}
}
