package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.mcp.McpDelegatedContextTransportExtractor;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.CorrelationHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP adapter for the non-authoritative diagnostic-probe recommendation agent (M2-ADR-032 step 3).
 *
 * <p>Follows {@link RamalsAiAdaptationClient} exactly -- the same transport, the same refusal to run
 * inside a database transaction, the same MCP-3.1 delegated-context header (M2-ADR-031), the same
 * deadline discipline. A separate client, not an overload of {@link RamalsAiDiagnosticAssessmentClient}:
 * the diagnostic-probe contract and its gate are distinct, so the call site is too.
 *
 * <p>Every failure -- transport, timeout, empty response -- raises {@link AiUnavailableException};
 * the orchestrator turns that into {@code ABSENT} and the deterministic diagnostic path is
 * unaffected.
 */
public class RamalsAiDiagnosticProbeClient implements DiagnosticProbePort {

  private static final Logger LOGGER = LoggerFactory.getLogger(RamalsAiDiagnosticProbeClient.class);

  private final RestClient restClient;
  private final AiCallGuard guard;
  private final WorkloadToken tokenProvider;

  public RamalsAiDiagnosticProbeClient(
      RestClient restClient, AiCallGuard guard, WorkloadToken tokenProvider) {
    this.restClient = restClient;
    this.guard = guard;
    this.tokenProvider =
        java.util.Objects.requireNonNull(
            tokenProvider, "a diagnostic-probe client must authenticate as the workload");
  }

  @Override
  public AiProposalEnvelope requestProbeRecommendation(
      AiRequestEnvelope request, long deadlineMillis) {
    return requestProbeRecommendation(request, deadlineMillis, DelegatedAiExecutionContext.NONE);
  }

  @Override
  public AiProposalEnvelope requestProbeRecommendation(
      AiRequestEnvelope request, long deadlineMillis, DelegatedAiExecutionContext delegatedContext) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("An AI call must not run inside a database transaction.");
    }
    if (deadlineMillis <= 0) {
      throw new AiUnavailableException(
          "AI_DEADLINE_EXCEEDED",
          "No time remained for a diagnostic-probe recommendation.",
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
                            .uri("/internal/v1/diagnostic-probe/propose")
                            // Workload identity per M1-ADR-003, never the learner's token.
                            .header("Authorization", "Bearer " + tokenProvider.accessToken())
                            .header(
                                CorrelationHeaders.INTERACTION_ID,
                                CorrelationContext.currentInteractionId())
                            // MCP-3.1 (M2-ADR-031): a distinct, independent credential answering
                            // "which learner/domain/capabilities may this interaction read through
                            // MCP", attached only when actually minted, never merged with the
                            // workload Authorization header above.
                            .headers(
                                headers ->
                                    delegatedContext
                                        .token()
                                        .ifPresent(
                                            token ->
                                                headers.add(
                                                    McpDelegatedContextTransportExtractor.HEADER_NAME,
                                                    token)))
                            .body(request)
                            .retrieve()
                            .body(AiProposalEnvelope.class);
                    DeadlineAwareClientHttpRequestFactory.requireRemaining();
                    if (proposal == null) {
                      throw new AiUnavailableException(
                          "AI_EMPTY_RESPONSE",
                          "The diagnostic-probe service returned no recommendation.");
                    }
                    return proposal;
                  } catch (RestClientException failure) {
                    String errorCode =
                        DeadlineAwareClientHttpRequestFactory.isExpired()
                            ? "AI_DEADLINE_EXCEEDED"
                            : "AI_TRANSPORT_FAILURE";
                    LOGGER
                        .atWarn()
                        .addKeyValue("operation", "ai.diagnosticProbe.call")
                        .addKeyValue("errorCode", errorCode)
                        .addKeyValue("errorType", failure.getClass().getSimpleName())
                        .log("diagnostic-probe call failed; no recommendation is adopted");
                    if ("AI_DEADLINE_EXCEEDED".equals(errorCode)) {
                      throw new AiUnavailableException(
                          "AI_DEADLINE_EXCEEDED",
                          "No time remained for a diagnostic-probe recommendation.",
                          DeadlineAwareClientHttpRequestFactory.currentFailureOrigin());
                    }
                    throw new AiUnavailableException(
                        "AI_TRANSPORT_FAILURE",
                        "The diagnostic-probe service could not be reached.");
                  }
                }));
  }
}
