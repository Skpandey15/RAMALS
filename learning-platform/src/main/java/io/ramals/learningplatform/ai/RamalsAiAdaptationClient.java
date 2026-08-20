package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.CorrelationHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP adapter for the non-authoritative Adaptation Agent. */
public class RamalsAiAdaptationClient implements AdaptationPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(RamalsAiAdaptationClient.class);

  private final RestClient restClient;
  private final AiCallGuard guard;
  private final WorkloadToken tokenProvider;

  public RamalsAiAdaptationClient(
      RestClient restClient, AiCallGuard guard, WorkloadToken tokenProvider) {
    this.restClient = restClient;
    this.guard = guard;
    this.tokenProvider = java.util.Objects.requireNonNull(
        tokenProvider, "an adaptation client must be able to authenticate as the workload");
  }

  @Override
  public AiProposalEnvelope requestAdaptationProposal(
      AiRequestEnvelope request, long deadlineMillis) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("An AI call must not run inside a database transaction.");
    }
    if (deadlineMillis <= 0) {
      throw new AiUnavailableException("AI_DEADLINE_EXCEEDED", "No time remained for adaptation.");
    }

    return DeadlineAwareClientHttpRequestFactory.execute(deadlineMillis, () ->
        guard.call(() -> {
          try {
            AiProposalEnvelope proposal = restClient
                .post()
                .uri("/internal/v1/adaptation/propose")
                // Workload identity per M1-ADR-003, never the learner's token.
                .header("Authorization", "Bearer " + tokenProvider.accessToken())
                .header(CorrelationHeaders.INTERACTION_ID, CorrelationContext.currentInteractionId())
                .body(request)
                .retrieve()
                .body(AiProposalEnvelope.class);
            DeadlineAwareClientHttpRequestFactory.requireRemaining();
            if (proposal == null) {
              throw new AiUnavailableException(
                  "AI_EMPTY_RESPONSE", "The adaptation service returned no proposal.");
            }
            return proposal;
          } catch (RestClientException failure) {
            String errorCode = DeadlineAwareClientHttpRequestFactory.isExpired()
                ? "AI_DEADLINE_EXCEEDED" : "AI_TRANSPORT_FAILURE";
            LOGGER.atWarn()
                .addKeyValue("operation", "ai.adaptation.call")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("errorType", failure.getClass().getSimpleName())
                .log("adaptation call failed; deterministic recommendation continues");
            if ("AI_DEADLINE_EXCEEDED".equals(errorCode)) {
              throw new AiUnavailableException(
                  "AI_DEADLINE_EXCEEDED", "No time remained for adaptation.");
            }
            throw new AiUnavailableException(
                "AI_TRANSPORT_FAILURE", "The adaptation service could not be reached.");
          }
        }));
  }
}
