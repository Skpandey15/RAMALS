package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.ai.WorkloadToken;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.CorrelationHeaders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the AI plane's Contract B surface.
 *
 * <p><strong>The only interesting thing this class does is classify failures.</strong> Everything
 * else is transport. A submission has three possible outcomes, not two, and getting the third one
 * right is the difference between Contract B and a duplicate provider execution:
 *
 * <ul>
 *   <li><em>Accepted</em> — a response carrying an execution identity.
 *   <li><em>Refused</em> — an HTTP status the provider plane chose deliberately. Nothing ran.
 *   <li><em>Unknown</em> — a timeout, a dropped connection, an unreadable body. The request may have
 *       reached the provider, and RAMALS cannot tell.
 * </ul>
 *
 * <p>The third is raised as {@link DurableSubmissionAmbiguousException} and never collapsed into the
 * second. A client that reported a timeout as a failure would invite a resubmission, and a
 * resubmission after an acknowledgement that was sent but not received is exactly how one logical
 * request becomes two provider executions.
 *
 * <p>Nothing here retries. Not on timeout, not on 5xx, not once. The retry decision belongs to the
 * component holding the durable row, and this client's job is to report faithfully what happened.
 *
 * <p>Refuses to run inside a transaction, like every other AI client in this codebase: a provider
 * call holding a database connection across a long deadline is the failure M1-ADR-001 exists to
 * prevent, and a Contract B call is the longest one the platform makes.
 */
public class RamalsAiDurableExecutionClient implements DurableExecutionPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(RamalsAiDurableExecutionClient.class);

  private final RestClient restClient;
  private final WorkloadToken tokenProvider;

  public RamalsAiDurableExecutionClient(RestClient restClient, WorkloadToken tokenProvider) {
    this.restClient = restClient;
    this.tokenProvider = java.util.Objects.requireNonNull(
        tokenProvider, "a durable execution client must authenticate as the workload");
  }

  @Override
  public DurableSubmissionAck submit(DurableSubmissionCommand command) {
    requireNoTransaction();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("request_id", command.requestId());
    body.put("idempotency_key", command.idempotencyKey());
    body.put("request_digest", command.requestDigest());
    body.put("model", command.model());
    body.put("max_output_tokens", command.maxOutputTokens());
    body.put("messages", command.messages().stream()
        .map(turn -> Map.of("role", turn.role(), "content", turn.content()))
        .toList());

    Map<?, ?> response;
    try {
      response = post("/internal/v1/durable/executions", body);
    } catch (RestClientResponseException refused) {
      // A status the far side chose. It knows nothing was submitted, so this is a refusal and the
      // caller may safely record a definite failure.
      LOGGER.warn("contract B submission refused [requestId={}, status={}]",
          command.requestId(), refused.getStatusCode().value());
      throw new DurableExecutionRefusedException(
          command.requestId(), refused.getStatusCode().value());
    } catch (ResourceAccessException | IllegalStateException unknown) {
      // A timeout, a reset connection, an unreadable body. The provider may be running this work.
      throw new DurableSubmissionAmbiguousException(command.requestId(),
          "the call did not complete readably");
    } catch (RestClientException unknown) {
      throw new DurableSubmissionAmbiguousException(command.requestId(),
          "the transport failed without a provider status");
    }

    if (response == null) {
      // A 2xx with no body is not an acknowledgement. Ambiguous rather than failed: something on
      // the far side may well have succeeded.
      throw new DurableSubmissionAmbiguousException(command.requestId(),
          "the acknowledgement carried no body");
    }
    return new DurableSubmissionAck(
        string(response, "provider_execution_id"),
        string(response, "state"),
        string(response, "custom_id"),
        string(response, "created_at"),
        string(response, "expires_at"));
  }

  @Override
  public DurableStatusSnapshot status(String providerExecutionId) {
    requireNoTransaction();
    Map<?, ?> response = get("/internal/v1/durable/executions/" + encode(providerExecutionId));
    if (response == null) {
      throw new IllegalStateException("contract B status response was empty");
    }
    Object retryAfter = response.get("retry_after_ms");
    return new DurableStatusSnapshot(
        string(response, "provider_execution_id"),
        string(response, "state"),
        string(response, "native_status"),
        Boolean.TRUE.equals(response.get("results_available")),
        retryAfter instanceof Number number ? number.intValue() : null);
  }

  @Override
  public DurableResultRecord result(String providerExecutionId, String customId) {
    requireNoTransaction();
    // custom_id in the query rather than the path: correlation is by the caller's own key, and a
    // result read by position is how one learner's diagnosis lands on another learner's record.
    String uri = "/internal/v1/durable/executions/" + encode(providerExecutionId) + "/result"
        + (customId == null ? "" : "?custom_id=" + encode(customId));
    Map<?, ?> response = get(uri);
    if (response == null) {
      throw new IllegalStateException("contract B result response was empty");
    }
    return new DurableResultRecord(
        string(response, "provider_execution_id"),
        string(response, "outcome"),
        string(response, "custom_id"),
        string(response, "text"),
        intOf(response, "input_tokens"),
        intOf(response, "output_tokens"),
        string(response, "provider_message_id"),
        string(response, "error_code"));
  }

  private Map<?, ?> post(String uri, Map<String, Object> body) {
    return restClient.post()
        .uri(uri)
        .header("Authorization", "Bearer " + tokenProvider.accessToken())
        .header(CorrelationHeaders.INTERACTION_ID, CorrelationContext.currentInteractionId())
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  private Map<?, ?> get(String uri) {
    return restClient.get()
        .uri(uri)
        .header("Authorization", "Bearer " + tokenProvider.accessToken())
        .header(CorrelationHeaders.INTERACTION_ID, CorrelationContext.currentInteractionId())
        .retrieve()
        .body(Map.class);
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("A Contract B provider call must not run in a transaction.");
    }
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String string(Map<?, ?> body, String key) {
    Object value = body.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static int intOf(Map<?, ?> body, String key) {
    return body.get(key) instanceof Number number ? number.intValue() : 0;
  }

  /** A refusal the far side chose. Distinct from ambiguity, which is the point of both types. */
  public static class DurableExecutionRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient HttpStatusCode status;

    DurableExecutionRefusedException(String requestId, int status) {
      super("contract B call refused [requestId=" + requestId + ", status=" + status + "]");
      this.status = HttpStatusCode.valueOf(status);
    }

    public HttpStatusCode status() {
      return status;
    }
  }
}
