package io.ramals.learningplatform.grounding;

import io.ramals.learningplatform.observability.UuidV7;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Append-only audit writer; retries reuse the proposal/policy identity. */
@Repository
public class JdbcProposalGateDecisionRepository implements ProposalGateDecisionPort {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbcTemplate;

  public JdbcProposalGateDecisionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void appendDecision(
      ProposalGroundingRequest proposal,
      ProposalGateResult result,
      DecisionCorrelation correlation) {
    List<String> reasons = result.reasons().stream().map(Enum::name).toList();
    List<String> evidenceIds = result.referencedEvidenceIds().stream().sorted().toList();
    jdbcTemplate.update("""
        INSERT INTO ledger.proposal_gate_decision
          (id, proposal_id, request_id, agent_run_id, context_id, proposal_type, accepted,
           reason_codes, referenced_evidence_ids, policy_version, interaction_id, trace_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
        ON CONFLICT (proposal_id, policy_version) DO NOTHING
        """, UuidV7.generate(), proposal.proposalId(), proposal.requestId(), proposal.agentRunId(),
        proposal.contextId(), proposal.proposalType().name(), result.accepted(),
        JSON.writeValueAsString(reasons), JSON.writeValueAsString(evidenceIds),
        ProposalGroundingPolicy.VERSION,
        correlation == null ? null : correlation.interactionId(),
        correlation == null ? null : correlation.traceId());
  }

  @Override
  public java.util.Optional<RecordedDecision> findDecision(
      String requestId, ProposalType proposalType) {
    if (requestId == null || requestId.isBlank() || proposalType == null) {
      return java.util.Optional.empty();
    }
    return jdbcTemplate
        .query(
            """
            SELECT request_id, proposal_id, agent_run_id, context_id, accepted, reason_codes
              FROM ledger.proposal_gate_decision
             WHERE request_id = ? AND proposal_type = ?
             ORDER BY decided_at DESC, id DESC
             LIMIT 1
            """,
            (result, row) ->
                new RecordedDecision(
                    result.getString("request_id"),
                    result.getString("proposal_id"),
                    result.getString("agent_run_id"),
                    result.getString("context_id"),
                    result.getBoolean("accepted"),
                    reasonCodes(result.getString("reason_codes"))),
            requestId,
            proposalType.name())
        .stream()
        .findFirst();
  }

  /**
   * Stored reason codes, or none.
   *
   * <p>Fails closed to an empty list rather than throwing: a recovering caller needs the accepted
   * flag, and losing the audit prose must not cost it the verdict.
   */
  private static List<String> reasonCodes(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      return List.of(JSON.readValue(raw, String[].class));
    } catch (tools.jackson.core.JacksonException unreadable) {
      return List.of();
    }
  }

  @Override
  public void appendPreParseRejection(PreParseRejection rejection) {
    DecisionCorrelation correlation = rejection.correlation();
    jdbcTemplate.update(
        """
        INSERT INTO ledger.proposal_gate_decision
          (id, proposal_id, request_id, agent_run_id, context_id, proposal_type, accepted,
           reason_codes, referenced_evidence_ids, policy_version, interaction_id, trace_id,
           parser_reason_code)
        VALUES (?, ?, ?, ?, ?, ?, false, CAST(? AS jsonb), '[]'::jsonb, ?, ?, ?, ?)
        ON CONFLICT (proposal_id, policy_version) DO NOTHING
        """,
        UuidV7.generate(),
        rejection.proposalId(),
        rejection.requestId(),
        rejection.agentRunId(),
        rejection.contextId(),
        rejection.proposalType().name(),
        JSON.writeValueAsString(List.of(rejection.publicReason().name())),
        ProposalGroundingPolicy.VERSION,
        correlation == null ? null : correlation.interactionId(),
        correlation == null ? null : correlation.traceId(),
        rejection.parserReasonCode());
  }
}
