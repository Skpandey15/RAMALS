package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.DiagnosticDispatchAuthorization;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.CorrelationHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the diagnostic assessment agent over the M2-T09 transport.
 *
 * <p>Follows the adaptation client exactly, including the refusal to run inside a database
 * transaction: an LLM call holding a connection open across a twelve-second deadline is the failure
 * M1-ADR-001 exists to prevent, and a second client is a second place to reintroduce it.
 */
public class RamalsAiDiagnosticAssessmentClient implements DiagnosticAssessmentPort {

  static final String DISPATCH_FENCE_HEADER = "X-RAMALS-Dispatch-Fence";
  static final String REQUEST_DIGEST_HEADER = "X-RAMALS-Request-Digest";

  private static final Logger LOGGER =
      LoggerFactory.getLogger(RamalsAiDiagnosticAssessmentClient.class);

  private final RestClient restClient;
  private final AiCallGuard guard;
  private final WorkloadToken tokenProvider;

  public RamalsAiDiagnosticAssessmentClient(
      RestClient restClient, AiCallGuard guard, WorkloadToken tokenProvider) {
    this.restClient = restClient;
    this.guard = guard;
    this.tokenProvider =
        java.util.Objects.requireNonNull(
            tokenProvider, "a diagnostic assessment client must authenticate as the workload");
  }

  @Override
  public AiProposalEnvelope requestDiagnosticAssessment(
      DiagnosticAssessmentRequest request,
      DiagnosticDispatchAuthorization authorization,
      long deadlineMillis) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("An AI call must not run inside a database transaction.");
    }
    if (deadlineMillis <= 0) {
      throw new AiUnavailableException(
          "AI_DEADLINE_EXCEEDED", "No time remained for diagnostic assessment.",
          FailureOrigin.CALLER);
    }
    return DeadlineAwareClientHttpRequestFactory.execute(
        deadlineMillis,
        () ->
            guard.call(
                () -> {
                  try {
                    AiProposalEnvelope proposal =
                        restClient
                            .post()
                            .uri("/internal/v1/diagnostic-assessment/propose")
                            // Workload identity per M1-ADR-003, never the learner's token.
                            .header("Authorization", "Bearer " + tokenProvider.accessToken())
                             .header(
                                 CorrelationHeaders.INTERACTION_ID,
                                 CorrelationContext.currentInteractionId())
                            .header(DISPATCH_FENCE_HEADER, Long.toString(authorization.fence()))
                            .header(REQUEST_DIGEST_HEADER, authorization.requestDigest())
                            .body(request)
                            .retrieve()
                            .body(AiProposalEnvelope.class);
                    DeadlineAwareClientHttpRequestFactory.requireRemaining();
                    if (proposal == null) {
                      throw new AiUnavailableException(
                          "AI_EMPTY_RESPONSE",
                          "The diagnostic assessment service returned no proposal.");
                    }
                    return proposal;
                  } catch (RestClientException failure) {
                    String errorCode =
                        DeadlineAwareClientHttpRequestFactory.isExpired()
                            ? "AI_DEADLINE_EXCEEDED"
                            : "AI_TRANSPORT_FAILURE";
                    LOGGER
                        .atWarn()
                        .addKeyValue("operation", "ai.diagnosticAssessment.call")
                        .addKeyValue("errorCode", errorCode)
                        .addKeyValue("errorType", failure.getClass().getSimpleName())
                        .log("diagnostic assessment call failed; no proposal is adopted");
                    if ("AI_DEADLINE_EXCEEDED".equals(errorCode)) {
                      throw new AiUnavailableException(
                          "AI_DEADLINE_EXCEEDED",
                          "No time remained for diagnostic assessment.",
                          DeadlineAwareClientHttpRequestFactory.currentFailureOrigin());
                    }
                    throw new AiUnavailableException(
                        "AI_TRANSPORT_FAILURE",
                        "The diagnostic assessment service could not be reached.");
                  }
                }));
  }
}
