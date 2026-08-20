package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.CorrelationHeaders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Authenticated HTTP adapter for the non-authoritative Assessment Agent. */
public class RamalsAiAssessmentClient implements AssessmentPort {

  private final RestClient restClient;
  private final AiCallGuard guard;
  private final WorkloadToken tokenProvider;

  public RamalsAiAssessmentClient(
      RestClient restClient, AiCallGuard guard, WorkloadToken tokenProvider) {
    this.restClient = restClient;
    this.guard = guard;
    this.tokenProvider = java.util.Objects.requireNonNull(
        tokenProvider, "an assessment client must be able to authenticate as the workload");
  }

  @Override
  public AiProposalEnvelope requestAssessmentProposal(
      AiRequestEnvelope request, long deadlineMillis, String requestedDifficulty) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("An AI call must not run inside a database transaction.");
    }
    if (deadlineMillis <= 0) {
      throw new AiUnavailableException(
          "AI_DEADLINE_EXCEEDED", "No time remained for assessment.", FailureOrigin.CALLER);
    }

    return DeadlineAwareClientHttpRequestFactory.execute(deadlineMillis, () ->
        guard.call(() -> {
          try {
            AiRequestEnvelope commissionedRequest = new AiRequestEnvelope(
                request.contractVersion(), request.interactionId(), request.requestId(), request.learner(),
                request.learningContext(), request.domainContext(), request.learningGoalContext(),
                request.constraints(), requestedDifficulty);
            // Token acquisition is deliberately inside the same scope as the AI request. A slow
            // identity provider must not renew the assessment budget before model dispatch.
            AiProposalEnvelope proposal = restClient.post()
                .uri("/internal/v1/assessment/propose")
                .header("Authorization", "Bearer " + tokenProvider.accessToken())
                .header(CorrelationHeaders.INTERACTION_ID, CorrelationContext.currentInteractionId())
                .body(commissionedRequest)
                .retrieve()
                .body(AiProposalEnvelope.class);
            DeadlineAwareClientHttpRequestFactory.requireRemaining();
            if (proposal == null) {
              throw new AiUnavailableException(
                  "AI_EMPTY_RESPONSE", "The assessment service returned no proposal.");
            }
            return proposal;
          } catch (RestClientException failure) {
            if (DeadlineAwareClientHttpRequestFactory.isExpired()) {
              throw deadlineExceeded();
            }
            throw new AiUnavailableException(
                "AI_TRANSPORT_FAILURE", "The assessment service could not be reached.");
          }
        }));
  }

  /**
   * A deadline failure attributed to whoever actually caused it.
   *
   * <p>Origin comes from whether a request was started, not from the error code: an expired budget
   * before dispatch is ours, while a read timeout or a reply that arrived too late means the
   * dependency was contacted and did not answer in time. Both carry {@code AI_DEADLINE_EXCEEDED}
   * so the learner-facing behaviour and {@code TutorUnavailableReason} mapping are unchanged.
   */
  private static AiUnavailableException deadlineExceeded() {
    return new AiUnavailableException("AI_DEADLINE_EXCEEDED", "No time remained for assessment.",
        DeadlineAwareClientHttpRequestFactory.currentFailureOrigin());
  }
}
