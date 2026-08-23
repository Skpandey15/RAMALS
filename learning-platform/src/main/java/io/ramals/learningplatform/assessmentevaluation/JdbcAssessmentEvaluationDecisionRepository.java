package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationDecisionRecord;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DimensionResult;
import io.ramals.learningplatform.observability.UuidV7;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    String digest = digest(record);
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
           policy_version, decision_digest, interaction_id, trace_id)
        SELECT ?, ?, ?, ?, execution.id, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
               CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?
          FROM core.ai_execution execution
         WHERE execution.request_id = ?
           AND execution.agent_run_id = ?
           AND execution.agent_type = 'ASSESSMENT'
           AND execution.status = 'SUCCEEDED'
        ON CONFLICT (request_id) DO NOTHING
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
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "evaluation decision requires a matching successful ASSESSMENT ai_execution"));
    if (!digest.equals(stored.digest())) {
      throw new AssessmentEvaluationReplayConflictException(
          "evaluation requestId was replayed with different decision content");
    }
    if (!record.decision().outcome().name().equals(stored.outcome())) {
      throw new AssessmentEvaluationReplayConflictException(
          "evaluation requestId was replayed with a different gate outcome");
    }
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
    canonical.put("interactionId", record.interactionId());
    canonical.put("traceId", record.traceId());
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
        || !bounded(record.traceId())) {
      throw new IllegalArgumentException("a complete, bounded evaluation decision is required");
    }
  }

  private static boolean bounded(String value) {
    return value != null && !value.isBlank() && value.length() <= 64;
  }

  private record StoredDecision(String digest, String outcome) {}
}
