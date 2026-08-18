package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;

/** Outbound port for non-authoritative assessment proposals. */
public interface AssessmentPort {

  AiProposalEnvelope requestAssessmentProposal(
      AiRequestEnvelope request, long deadlineMillis, String requestedDifficulty);
}
