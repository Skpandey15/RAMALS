package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.observability.UuidV7;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only audit writer for advisory diagnostic-probe proposal decisions (M2-ADR-032 19).
 * Retries reuse the {@code (proposal_id, policy_version)} identity, so a re-evaluated identical
 * proposal collapses to one row rather than raising.
 */
@Repository
public class JdbcDiagnosticProbeProposalDecisionRepository
    implements DiagnosticProbeProposalDecisionPort {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbcTemplate;

  public JdbcDiagnosticProbeProposalDecisionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void append(DiagnosticProbeProposalDecision decision) {
    jdbcTemplate.update(
        """
        INSERT INTO ledger.diagnostic_probe_proposal_decision
          (id, proposal_id, request_id, agent_run_id, interaction_id, trace_id, learner_id,
           domain_code, proposal_contract_version, policy_version, accepted, reason_codes,
           parser_reason_code, target_misconception_id, target_node_kind, target_node_id,
           probe_intent, candidate_probe_ref, allowed_evidence_refs, cited_evidence_refs,
           prompt_template_id, prompt_version, model_route, resolved_provider, model_id,
           route_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?,
                CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
        ON CONFLICT (proposal_id, policy_version) DO NOTHING
        """,
        UuidV7.generate(),
        decision.proposalId(),
        decision.requestId(),
        decision.agentRunId(),
        decision.interactionId(),
        decision.traceId(),
        decision.learnerId(),
        decision.domainCode(),
        decision.proposalContractVersion(),
        decision.policyVersion(),
        decision.accepted(),
        JSON.writeValueAsString(decision.reasonCodes()),
        decision.parserReasonCode(),
        decision.targetMisconceptionId(),
        decision.targetNodeKind(),
        decision.targetNodeId(),
        decision.probeIntent(),
        decision.candidateProbeRef(),
        JSON.writeValueAsString(decision.allowedEvidenceRefs()),
        JSON.writeValueAsString(decision.citedEvidenceRefs()),
        decision.promptTemplateId(),
        decision.promptVersion(),
        decision.modelRoute(),
        decision.resolvedProvider(),
        decision.modelId(),
        decision.routeVersion());
  }

  @Override
  public Optional<RecordedProbeDecision> findByProposalId(String proposalId) {
    if (proposalId == null || proposalId.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate
        .query(
            """
            SELECT proposal_id, interaction_id, accepted, reason_codes, parser_reason_code,
                   policy_version
              FROM ledger.diagnostic_probe_proposal_decision
             WHERE proposal_id = ? AND policy_version = ?
             ORDER BY decided_at DESC, id DESC
             LIMIT 1
            """,
            (result, row) ->
                new RecordedProbeDecision(
                    result.getString("proposal_id"),
                    result.getString("interaction_id"),
                    result.getBoolean("accepted"),
                    reasonCodes(result.getString("reason_codes")),
                    result.getString("parser_reason_code"),
                    result.getString("policy_version")),
            proposalId,
            DiagnosticProbeProposalGate.POLICY_VERSION)
        .stream()
        .findFirst();
  }

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
}
