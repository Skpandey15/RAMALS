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
}
