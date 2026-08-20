package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.CorrelationHeaders;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP adapter to the AI execution plane.
 *
 * <p>Everything interesting here is about what must <em>not</em> happen.
 *
 * <p><b>No database transaction may be open.</b> An AI call takes seconds; a PostgreSQL connection
 * held across it is a connection not serving anyone else, and enough of them is an outage of the
 * deterministic core caused by a slow model. This is asserted at runtime rather than left to review,
 * because the mistake is easy to make — adding {@code @Transactional} to a service method that also
 * calls a tutor is a single annotation, and nothing else would notice.
 *
 * <p><b>No learner token is forwarded.</b> The workload identity from M1-ADR-003 authenticates this
 * call. Passing the learner's token through would be privilege laundering: the AI plane could then
 * act as the learner, and the platform could no longer distinguish "the learner asked for this" from
 * "a model decided to do this".
 */
public class RamalsAiTutorClient implements TutorPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(RamalsAiTutorClient.class);

  private final RestClient restClient;
  private final AiCallGuard guard;
  private final WorkloadToken tokenProvider;

  public RamalsAiTutorClient(
      RestClient restClient, AiCallGuard guard, WorkloadToken tokenProvider) {
    this.restClient = restClient;
    this.guard = guard;
    // Required, not optional. The AI plane rejects an unauthenticated request, so a client built
    // without a token provider is a client that can only ever produce 401s -- which degrade to
    // "tutoring unavailable" and look exactly like the AI plane being down.
    this.tokenProvider = java.util.Objects.requireNonNull(
        tokenProvider, "a tutor client must be able to authenticate as the workload");
  }

  @Override
  public AiProposalEnvelope requestTutorResponse(AiRequestEnvelope request, long deadlineMillis) {
    assertNoOpenTransaction();

    if (deadlineMillis <= 0) {
      throw new AiUnavailableException("AI_DEADLINE_EXCEEDED",
          "No time remained to consult the tutoring service.");
    }

    return DeadlineAwareClientHttpRequestFactory.execute(deadlineMillis, () ->
        guard.call(() -> {
          long startedAt = System.nanoTime();
          try {
            AiProposalEnvelope proposal = restClient
                .post()
                .uri("/internal/v1/tutor/respond")
                // Workload identity per M1-ADR-003. Never the learner's token: forwarding that
                // would let the AI plane act as the learner.
                .header("Authorization", "Bearer " + tokenProvider.accessToken())
                // The AI plane rejects a request without a canonical interactionId, and rightly: a
                // proposal nobody can correlate is a proposal nobody can investigate.
                .header(CorrelationHeaders.INTERACTION_ID, CorrelationContext.currentInteractionId())
                .body(request)
                .retrieve()
                .body(AiProposalEnvelope.class);

            DeadlineAwareClientHttpRequestFactory.requireRemaining();
            if (proposal == null) {
              throw new AiUnavailableException("AI_EMPTY_RESPONSE",
                  "The tutoring service returned no proposal.");
            }
            LOGGER.atInfo()
                .addKeyValue("operation", "ai.tutor.call")
                .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
                .addKeyValue("proposalId", proposal.proposalId())
                .addKeyValue("modelRoute", proposal.modelRoute())
                .log("tutor proposal received");
            return proposal;
          } catch (RestClientException transportFailure) {
            // The message is deliberately not propagated to the learner: a transport error can carry a
            // host name, a URL or a response body, none of which belong on a learner's screen.
            String errorCode = DeadlineAwareClientHttpRequestFactory.isExpired()
                ? "AI_DEADLINE_EXCEEDED" : "AI_TRANSPORT_FAILURE";
            LOGGER.atWarn()
                .addKeyValue("operation", "ai.tutor.call")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("errorType", transportFailure.getClass().getSimpleName())
                .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
                .log("tutor call failed");
            if ("AI_DEADLINE_EXCEEDED".equals(errorCode)) {
              throw new AiUnavailableException("AI_DEADLINE_EXCEEDED",
                  "No time remained to consult the tutoring service.");
            }
            throw new AiUnavailableException("AI_TRANSPORT_FAILURE",
                "The tutoring service could not be reached.");
          }
        }));
  }

  /**
   * Fails loudly if a transaction is open around the AI call.
   *
   * <p>An instrumented assertion rather than a test-only check: the condition it guards against is
   * introduced by adding an annotation somewhere else entirely, so the place it must be detected is
   * here, at runtime, on every call.
   */
  private void assertNoOpenTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      LOGGER.atError()
          .addKeyValue("operation", "ai.tutor.call")
          .addKeyValue("errorCode", "AI_CALL_INSIDE_TRANSACTION")
          .log("refused to call the AI plane with a database transaction open");
      throw new IllegalStateException(
          "An AI call must not run inside a database transaction: a connection held for the "
              + "duration of a model call is a connection the deterministic core cannot use.");
    }
  }

  /** Timeouts a caller should configure on the underlying client, from Doc 01 INTERACTIVE_AI. */
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  public static final Duration READ_TIMEOUT = Duration.ofSeconds(12);
}
