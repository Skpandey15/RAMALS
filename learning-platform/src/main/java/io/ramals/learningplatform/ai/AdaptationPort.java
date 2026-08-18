package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;

/** Outbound port for non-authoritative adaptation proposals. */
public interface AdaptationPort {

  /** Requests a proposal without granting the AI plane decision or write authority. */
  AiProposalEnvelope requestAdaptationProposal(AiRequestEnvelope request, long deadlineMillis);
}
