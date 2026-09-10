package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;

/**
 * Outbound port for the advisory diagnostic-probe recommendation agent (M2-ADR-032 step 3).
 *
 * <p>Proposal-only, in the strong sense: the returned {@link AiProposalEnvelope} carries a bounded
 * recommendation and nothing else. It is never authority -- Java's {@code
 * DiagnosticProbeProposalGate} independently decides whether it has any effect, and an accepted
 * recommendation is recorded and consumed by nothing (M2-ADR-032 6). Distinct from {@link
 * DiagnosticAssessmentPort} on purpose: a different contract, a different gate, no shared transport.
 */
public interface DiagnosticProbePort {

  /** Requests a recommendation without granting the AI plane decision or write authority. */
  AiProposalEnvelope requestProbeRecommendation(AiRequestEnvelope request, long deadlineMillis);

  /**
   * Same request, additionally carrying MCP-3.1's delegated learner-context credential as a
   * transport-only header -- never part of the request body (M2-ADR-031). A default forwarding to
   * the two-argument form, so a caller/test with no delegated context to offer is unaffected; a
   * real client overrides only this method.
   */
  default AiProposalEnvelope requestProbeRecommendation(
      AiRequestEnvelope request, long deadlineMillis, DelegatedAiExecutionContext delegatedContext) {
    return requestProbeRecommendation(request, deadlineMillis);
  }
}
