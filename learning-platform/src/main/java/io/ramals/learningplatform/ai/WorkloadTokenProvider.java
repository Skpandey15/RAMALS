package io.ramals.learningplatform.ai;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client-credentials token cache for Spring's authenticated calls to ramals-ai.
 *
 * <p>One instance is shared by the tutor, adaptation and assessment clients. Each used to build its
 * own, which meant three independent caches refreshing on three schedules and three times the
 * client-credentials grants against the identity provider — and a per-client rate limit reached
 * three times sooner.
 *
 * <p>Sharing one cache makes its locking matter, so the lock is only taken to refresh. A valid token
 * is returned from a volatile read with no lock at all; previously even a cache hit acquired the
 * monitor, which with a single shared instance would have serialised every AI caller in the process
 * behind each other's reads.
 *
 * <p>Failures here are reported as {@code AI_IDENTITY_FAILURE}, not as an AI transport failure. The
 * distinction is operational: the AI plane may be perfectly healthy while the identity provider is
 * down, and an operator told "the tutoring service could not be reached" will go and look at the
 * wrong service. It is also attributed to {@link FailureOrigin#CALLER}, which for the AI plane's
 * circuit breaker means "not evidence about the AI plane" — a slow identity provider must not open
 * a breaker that protects something it never touched. That is not a claim the identity provider is
 * healthy; its own health is a separate question.
 */
public final class WorkloadTokenProvider implements WorkloadToken {

  /** Refresh this long before expiry, so a token is not spent at the moment it is presented. */
  private static final long REFRESH_MARGIN_SECONDS = 10L;

  /** Ceiling on how long a caller waits for someone else's refresh when it has no deadline. */
  private static final long MAX_REFRESH_WAIT_MILLIS = 30_000L;

  /** An issued token and the moment it stops being usable. Immutable so it publishes safely. */
  private record CachedToken(String token, Instant usableUntil) {
    boolean usableNow() {
      return Instant.now().isBefore(usableUntil);
    }
  }

  private final RestClient tokenClient;
  private final String clientId;
  private final String clientSecret;
  private final String audience;

  private final ReentrantLock refreshLock = new ReentrantLock();
  private volatile CachedToken cached;

  public WorkloadTokenProvider(
      RestClient tokenClient, String clientId, String clientSecret, String audience) {
    this.tokenClient = tokenClient;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.audience = audience;
  }

  @Override
  public String accessToken() {
    CachedToken current = cached;
    if (current != null && current.usableNow()) {
      return current.token();
    }
    return refresh();
  }

  private String refresh() {
    // Bounded by the caller's remaining budget where there is one. Without this a shared cache
    // could hold a caller past its own deadline waiting for another thread's refresh — trading the
    // triple-grant problem for a queueing one.
    long waitMillis =
        Math.min(DeadlineAwareClientHttpRequestFactory.remainingMillis(), MAX_REFRESH_WAIT_MILLIS);
    boolean held;
    try {
      held = refreshLock.tryLock(Math.max(waitMillis, 0L), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw identityFailure("interrupted while waiting for a workload token");
    }
    if (!held) {
      throw identityFailure("timed out waiting for a workload token");
    }

    try {
      // Re-read inside the lock: while this thread queued, another may have refreshed already, and
      // fetching again would spend a grant for a token we now hold.
      CachedToken current = cached;
      if (current != null && current.usableNow()) {
        return current.token();
      }

      CachedToken issued = fetch();
      cached = issued;
      return issued.token();
    } finally {
      refreshLock.unlock();
    }
  }

  private CachedToken fetch() {
    Map<?, ?> response;
    try {
      response = tokenClient.post()
          .header("Content-Type", "application/x-www-form-urlencoded")
          .body("grant_type=client_credentials&client_id=" + encode(clientId)
              + "&client_secret=" + encode(clientSecret) + "&audience=" + encode(audience))
          .retrieve()
          .body(Map.class);
    } catch (RestClientException failure) {
      // Deliberately not propagated: the message can carry the token endpoint's host, URL or
      // response body, none of which belong in an error that reaches a learner-facing path.
      throw identityFailure("the identity provider could not be reached");
    }

    if (response == null || !(response.get("access_token") instanceof String issued)
        || issued.isBlank()) {
      throw identityFailure("the identity provider returned no access token");
    }

    long expiresIn = response.get("expires_in") instanceof Number number ? number.longValue() : 60L;
    return new CachedToken(
        issued, Instant.now().plusSeconds(Math.max(1L, expiresIn - REFRESH_MARGIN_SECONDS)));
  }

  private static AiUnavailableException identityFailure(String detail) {
    return new AiUnavailableException("AI_IDENTITY_FAILURE",
        "The AI workload identity could not be obtained: " + detail + ".", FailureOrigin.CALLER);
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
