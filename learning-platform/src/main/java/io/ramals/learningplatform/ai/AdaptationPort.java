package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;

/** Outbound port for non-authoritative adaptation proposals. */
public interface AdaptationPort {

  /** Requests a proposal without granting the AI plane decision or write authority. */
  AiProposalEnvelope requestAdaptationProposal(AiRequestEnvelope request, long deadlineMillis);

  /**
   * Same request, additionally carrying MCP-3.1's delegated learner-context credential as a
   * transport-only header -- never part of the request body (M2-ADR-031). A default forwarding to
   * the two-argument form, not a changed signature: every existing caller/test that has no
   * delegated context to offer is unaffected, and a real client overrides only this method.
   */
  default AiProposalEnvelope requestAdaptationProposal(
      AiRequestEnvelope request, long deadlineMillis, DelegatedAiExecutionContext delegatedContext) {
    return requestAdaptationProposal(request, deadlineMillis);
  }
}
