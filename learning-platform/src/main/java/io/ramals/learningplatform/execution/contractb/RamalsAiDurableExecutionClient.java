package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.ai.WorkloadToken;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.CorrelationHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  /**
   * The AI plane's marker for "no provider execution was created".
   *
   * <p>Kept as a constant on both sides of the boundary and asserted by a contract test, because a
   * typo here would silently turn every definite refusal into an ambiguous one — safe, but it would
   * quietly fill the ledger with executions needing an operator that never needed one.
   */
  static final String SUBMISSION_NOT_CREATED = "NOT_CREATED";

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
    } catch (RestClientResponseException answered) {
      // A status the far side chose -- which is *not* the same as knowing nothing was created.
      //
      // This used to treat any status as a definite refusal, and that failed open in the one
      // direction it must not. The same 500 covers "the SDK was missing" and "the connection
      // dropped after create was sent", and only the AI plane can tell those apart. Recording
      // FAILED for the second says RAMALS knows no provider execution exists, when one may be
      // running and billing under a name RAMALS never learned.
      //
      // So the classification is read from what the far side *says*, not from the status it chose.
      // A definite refusal requires an explicit NOT_CREATED; anything else -- an unrecognised body,
      // a proxy's own error page, a status raised by infrastructure that never reached the AI
      // plane -- is ambiguous by default and fails closed.
      if (creationRuledOut(answered)) {
        LOGGER.warn("contract B submission refused before creation [requestId={}, status={}]",
            command.requestId(), answered.getStatusCode().value());
        throw new DurableExecutionRefusedException(
            command.requestId(), answered.getStatusCode().value());
      }
      LOGGER.warn("contract B submission failed and creation cannot be ruled out [requestId={}, "
          + "status={}]. Treated as ambiguous: a provider execution may exist.",
          command.requestId(), answered.getStatusCode().value());
      throw new DurableSubmissionAmbiguousException(command.requestId(),
          "the submission failed with a status that does not rule out provider-side creation");
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

  /**
   * Whether the AI plane explicitly stated that no provider execution was created.
   *
   * <p>The marker is a positive claim and is required to be present, spelled exactly, and made by
   * the AI plane itself. Its absence is not evidence of anything — a proxy error page, a gateway
   * timeout, a body this build cannot parse and a response that never reached the AI plane all
   * arrive with no marker, and none of them rules creation out.
   *
   * <p>Reading it never throws. A classifier that could fail while classifying a failure would
   * decide the outcome by accident, so anything unreadable simply means "not stated", which is the
   * safe answer.
   */
  private static boolean creationRuledOut(RestClientResponseException answered) {
    try {
      Map<?, ?> body = answered.getResponseBodyAs(Map.class);
      if (body == null) {
        return false;
      }
      Object detail = body.get("detail");
      return detail instanceof Map<?, ?> stated
          && SUBMISSION_NOT_CREATED.equals(String.valueOf(stated.get("submission")));
    } catch (RuntimeException unreadable) {
      return false;
    }
  }

  @Override
  public DurableExecutionSearch search(String customId, String from, String to,
      int maxInspections, java.util.Collection<String> excludeIds) {
    requireNoTransaction();
    // Every parameter is encoded. custom_id is server-derived rather than caller-supplied, so this
    // is not an injection boundary, but a correlation key travelling in a query string is exactly
    // the sort of value that acquires a colon or a plus sign later.
    StringBuilder uri = new StringBuilder("/internal/v1/durable/executions")
        .append("?custom_id=").append(encode(customId))
        .append("&created_after=").append(encode(from))
        .append("&created_before=").append(encode(to))
        .append("&max_inspections=").append(Math.max(1, maxInspections));
    // The batches an earlier search already proved are not this request's (M2-ADR-020 §3.1). Sent
    // rather than remembered on the far side, because M2-ADR-017 §1 makes the AI plane stateless:
    // a cache living there would die with the process and repay its whole cost after every restart.
    // Bounded by the inspection budget that produced them, so the query string stays small.
    for (String excluded : excludeIds) {
      uri.append("&exclude_id=").append(encode(excluded));
    }

    Map<?, ?> response = get(uri.toString());
    if (response == null) {
      // An empty body is not an empty result set. Reporting ZERO here would let a caller conclude
      // no orphan exists on the strength of a response nobody could read.
      throw new IllegalStateException("contract B search response was empty");
    }

    List<DiscoveredExecution> matches = new ArrayList<>();
    if (response.get("matches") instanceof List<?> raw) {
      for (Object entry : raw) {
        if (entry instanceof Map<?, ?> match) {
          matches.add(new DiscoveredExecution(
              string(match, "provider_execution_id"),
              string(match, "custom_id"),
              string(match, "outcome"),
              boxedInt(match, "input_tokens"),
              boxedInt(match, "output_tokens"),
              boxedInt(match, "cached_input_tokens"),
              string(match, "created_at"),
              string(match, "ended_at"),
              string(match, "native_status")));
        }
      }
    }
    List<String> newlyExcluded = new ArrayList<>();
    if (response.get("newly_excluded_ids") instanceof List<?> raw) {
      for (Object entry : raw) {
        if (entry != null) {
          newlyExcluded.add(String.valueOf(entry));
        }
      }
    }

    return new DurableExecutionSearch(
        DurableExecutionSearch.Outcome.of(string(response, "outcome")),
        matches,
        intOf(response, "batches_listed"),
        intOf(response, "batches_inspected"),
        intOf(response, "batches_uninspectable"),
        intOf(response, "pages_fetched"),
        string(response, "limit_reached"),
        intOf(response, "batches_excluded"),
        newlyExcluded);
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
    return correlated(restClient.post().uri(uri))
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  /**
   * Adds workload identity, and the interaction id only when there is one.
   *
   * <p>Sending an empty header is strictly worse than sending none. The AI plane accepts a missing
   * correlation and generates one; it rejects an empty one as malformed, which is how a thread with
   * no MDC turned every Contract B call into a 400. Omitting is the contract the AI plane actually
   * defines, and it keeps this transport from inventing identifiers — establishing correlation is
   * the caller's job, and the reconciliation worker now does it per execution.
   */
  private <T extends org.springframework.web.client.RestClient.RequestHeadersSpec<T>> T correlated(
      org.springframework.web.client.RestClient.RequestHeadersSpec<T> spec) {
    T withIdentity = spec.header("Authorization", "Bearer " + tokenProvider.accessToken());
    String interactionId = CorrelationContext.currentInteractionId();
    return interactionId == null || interactionId.isBlank()
        ? withIdentity
        : withIdentity.header(CorrelationHeaders.INTERACTION_ID, interactionId);
  }

  private Map<?, ?> get(String uri) {
    try {
      return correlated(restClient.get().uri(uri))
          .retrieve()
          .body(Map.class);
    } catch (RestClientResponseException failed) {
      // A rate limit is not an outage and must not be retried like one (M2-ADR-020 §7). Every other
      // status keeps the behaviour it had: it reaches the caller as a RuntimeException, says
      // nothing about whether an orphan exists, and is therefore never terminal on its own.
      if (failed.getStatusCode().value() == 429) {
        throw new DurableExecutionRateLimitedException(
            "the provider plane reported a rate limit", retryAfterMillis(failed));
      }
      throw failed;
    }
  }

  /**
   * The provider's own {@code Retry-After}, in milliseconds, or null if it did not say.
   *
   * <p>Seconds only. The HTTP-date form is legal and deliberately not parsed: it is vanishingly rare
   * from this provider, and the caller's exponential backoff is a correct fallback, so parsing it
   * would add a date-skew bug to save nothing.
   */
  private static Long retryAfterMillis(RestClientResponseException failed) {
    String header = failed.getResponseHeaders() == null
        ? null
        : failed.getResponseHeaders().getFirst("Retry-After");
    if (header == null || header.isBlank()) {
      return null;
    }
    try {
      long seconds = Long.parseLong(header.trim());
      return seconds < 0 ? null : seconds * 1000L;
    } catch (NumberFormatException notSeconds) {
      return null;
    }
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

  /** Null rather than zero when absent: an unreported token count is not a count of nothing. */
  private static Integer boxedInt(Map<?, ?> body, String key) {
    return body.get(key) instanceof Number number ? number.intValue() : null;
  }

}
