package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;

/** The outbound boundary to the diagnostic assessment agent. Proposals only; never authority. */
public interface DiagnosticAssessmentPort {

  AiProposalEnvelope requestDiagnosticAssessment(
      DiagnosticAssessmentRequest request, long deadlineMillis);
}
