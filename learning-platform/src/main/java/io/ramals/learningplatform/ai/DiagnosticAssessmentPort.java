package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticDispatchAuthorization;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;

/** The outbound boundary to the diagnostic assessment agent. Proposals only; never authority. */
public interface DiagnosticAssessmentPort {

  AiProposalEnvelope requestDiagnosticAssessment(
      DiagnosticAssessmentRequest request,
      DiagnosticDispatchAuthorization authorization,
      long deadlineMillis);

  /**
   * Same request, additionally carrying MCP-3.1's delegated learner-context credential as a
   * transport-only header -- never part of the request body (M2-ADR-031). A default forwarding to
   * the three-argument form, not a changed signature: every existing caller/test that has no
   * delegated context to offer is unaffected, and a real client overrides only this method.
   */
  default AiProposalEnvelope requestDiagnosticAssessment(
      DiagnosticAssessmentRequest request,
      DiagnosticDispatchAuthorization authorization,
      long deadlineMillis,
      DelegatedAiExecutionContext delegatedContext) {
    return requestDiagnosticAssessment(request, authorization, deadlineMillis);
  }
}
