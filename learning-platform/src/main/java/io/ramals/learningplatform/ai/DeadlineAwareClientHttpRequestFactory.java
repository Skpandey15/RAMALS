package io.ramals.learningplatform.ai;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Applies the caller's remaining AI budget to every synchronous HTTP request made in its scope.
 *
 * <p>{@link SimpleClientHttpRequestFactory} stores connect and read timeouts on the factory, so a
 * fixed timeout alone cannot honour a shorter request deadline. This factory keeps the absolute
 * deadline in a thread-local scope and clamps both transport timeouts when a request is created.
 * The scope is deliberately local to the learning-platform process; no deadline state is sent to,
 * or read from, the non-authoritative AI plane.
 */
final class DeadlineAwareClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

  private static final long NANOS_PER_MILLISECOND = 1_000_000L;
  private static final long NANOS_PER_MILLISECOND_MINUS_ONE = NANOS_PER_MILLISECOND - 1;

  private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

  /**
   * Whether a request to <em>the AI plane</em> was started in the current scope.
   *
   * <p>This is the fact that separates "we ran out of time before asking" from "we asked and they
   * did not answer". Both surface as {@code AI_DEADLINE_EXCEEDED}, and only the second is evidence
   * about the AI plane's health, so the distinction cannot be recovered from the error code and must
   * be recorded when it happens.
   *
   * <p>Specifically the AI plane, not "some request". Assessment acquires a workload token from the
   * identity provider inside the same deadline scope — deliberately, so a slow identity provider
   * cannot extend the model budget. A marker meaning "something was dispatched" would therefore be
   * set by the token call, and a budget consumed entirely by the identity provider would be recorded
   * as the AI plane failing to answer while the AI plane was never contacted. Three of those would
   * open its circuit because Keycloak was slow.
   */
  private static final ThreadLocal<Boolean> AI_PLANE_DISPATCHED = new ThreadLocal<>();

  /** Whether requests from this factory count as contacting the AI plane. */
  private final boolean marksAiPlaneDispatch;

  private DeadlineAwareClientHttpRequestFactory(boolean marksAiPlaneDispatch) {
    this.marksAiPlaneDispatch = marksAiPlaneDispatch;
  }

  /** A transport for calls to the AI plane. Requests it starts are attributed to the AI plane. */
  static DeadlineAwareClientHttpRequestFactory forAiPlane() {
    return new DeadlineAwareClientHttpRequestFactory(true);
  }

  /**
   * A transport for a call made on the way to the AI plane, such as acquiring a workload token.
   *
   * <p>Shares the caller's deadline exactly as the AI transport does — that part is deliberate — but
   * contacting this service is not evidence about the AI plane, so it never marks AI-plane dispatch.
   * Whether the identity provider is itself healthy is a separate question with a separate answer;
   * this only keeps it out of the AI plane's breaker.
   */
  static DeadlineAwareClientHttpRequestFactory forSupportingCall() {
    return new DeadlineAwareClientHttpRequestFactory(false);
  }

  @Override
  public org.springframework.http.client.ClientHttpRequest createRequest(
      URI uri, HttpMethod httpMethod) throws IOException {
    requireRemaining();
    org.springframework.http.client.ClientHttpRequest request =
        super.createRequest(uri, httpMethod);
    // Recorded here rather than after a successful response: from this point the AI plane is being
    // contacted, so a connect timeout, a read timeout or a late reply are all about it.
    if (marksAiPlaneDispatch) {
      AI_PLANE_DISPATCHED.set(Boolean.TRUE);
    }
    return request;
  }

  /** Whether the current scope has started a request to the AI plane. */
  static boolean aiPlaneDispatchAttempted() {
    return Boolean.TRUE.equals(AI_PLANE_DISPATCHED.get());
  }

  /**
   * The origin to attribute a failure to, for the breaker that protects the AI plane.
   *
   * <p>Derived from an observed fact rather than from the error code, which cannot tell the cases
   * apart. {@code CALLER} here means "not evidence about the AI plane" — which covers a budget that
   * expired before dispatch and a budget spent reaching the identity provider alike.
   */
  static FailureOrigin currentFailureOrigin() {
    return aiPlaneDispatchAttempted() ? FailureOrigin.DEPENDENCY : FailureOrigin.CALLER;
  }

  @Override
  protected void prepareConnection(HttpURLConnection connection, String httpMethod)
      throws IOException {
    super.prepareConnection(connection, httpMethod);

    Long deadlineNanos = DEADLINE_NANOS.get();
    if (deadlineNanos == null) {
      return;
    }

    int remainingMillis = remainingTimeoutMillis(deadlineNanos);
    connection.setConnectTimeout(clampTimeout(connection.getConnectTimeout(), remainingMillis));
    connection.setReadTimeout(clampTimeout(connection.getReadTimeout(), remainingMillis));
  }

  /**
   * Runs a synchronous AI operation under a caller-provided remaining budget.
   *
   * <p>An existing outer scope wins when it expires sooner. This makes nested use safe and avoids
   * accidentally extending a request deadline while obtaining an assessment workload token.
   */
  static <T> T execute(long deadlineMillis, Supplier<T> action) {
    Objects.requireNonNull(action, "action must not be null");
    if (deadlineMillis <= 0) {
      throw deadlineExceeded();
    }

    Long previousDeadline = DEADLINE_NANOS.get();
    Boolean previousDispatch = AI_PLANE_DISPATCHED.get();
    AI_PLANE_DISPATCHED.remove();
    long requestedDeadline = deadlineNanosFromNow(deadlineMillis);
    long effectiveDeadline = previousDeadline == null
        ? requestedDeadline
        : earlierDeadline(previousDeadline, requestedDeadline);
    DEADLINE_NANOS.set(effectiveDeadline);
    try {
      requireRemaining();
      T result = action.get();
      requireRemaining();
      return result;
    } finally {
      if (previousDeadline == null) {
        DEADLINE_NANOS.remove();
      } else {
        DEADLINE_NANOS.set(previousDeadline);
      }
      // An inner scope's dispatch does not leak outwards. Together with the AI-plane-only marker
      // above, this keeps "was the AI plane reached" true to its name whether a supporting call is
      // made inline or inside its own nested budget.
      if (previousDispatch == null) {
        AI_PLANE_DISPATCHED.remove();
      } else {
        AI_PLANE_DISPATCHED.set(previousDispatch);
      }
    }
  }

  /** Returns whether the current scoped caller budget has expired. */
  static boolean isExpired() {
    Long deadlineNanos = DEADLINE_NANOS.get();
    return deadlineNanos != null && remainingNanos(deadlineNanos) <= 0;
  }

  /** Requires time to remain in the current scope. */
  static void requireRemaining() {
    if (isExpired()) {
      throw deadlineExceeded();
    }
  }

  /** Returns the current remaining budget, rounded up for use as a transport timeout. */
  static long remainingMillis() {
    Long deadlineNanos = DEADLINE_NANOS.get();
    if (deadlineNanos == null) {
      return Long.MAX_VALUE;
    }
    long remainingNanos = remainingNanos(deadlineNanos);
    if (remainingNanos <= 0) {
      return 0;
    }
    return ceilMillis(remainingNanos);
  }

  private static int clampTimeout(int configuredTimeout, int remainingMillis) {
    // A zero timeout means "wait forever" to HttpURLConnection. Once a deadline is active, a
    // positive value is required even when only a sub-millisecond fraction remains.
    int effectiveConfigured = configuredTimeout <= 0 ? Integer.MAX_VALUE : configuredTimeout;
    return Math.max(1, Math.min(effectiveConfigured, remainingMillis));
  }

  private static int remainingTimeoutMillis(long deadlineNanos) throws IOException {
    long remainingNanos = remainingNanos(deadlineNanos);
    long remainingMillis = remainingNanos <= 0 ? 0 : ceilMillis(remainingNanos);
    if (remainingMillis <= 0) {
      throw new IOException("AI caller deadline exceeded before opening the connection");
    }
    return (int) Math.min(Integer.MAX_VALUE, remainingMillis);
  }

  private static long deadlineNanosFromNow(long deadlineMillis) {
    long budgetNanos;
    try {
      budgetNanos = Math.multiplyExact(deadlineMillis, NANOS_PER_MILLISECOND);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }

    try {
      return Math.addExact(System.nanoTime(), budgetNanos);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static long earlierDeadline(long first, long second) {
    if (first == Long.MAX_VALUE) {
      return second;
    }
    if (second == Long.MAX_VALUE) {
      return first;
    }
    return first <= second ? first : second;
  }

  private static long remainingNanos(long deadlineNanos) {
    return deadlineNanos - System.nanoTime();
  }

  private static long ceilMillis(long nanos) {
    if (nanos >= Long.MAX_VALUE - NANOS_PER_MILLISECOND_MINUS_ONE) {
      return Long.MAX_VALUE;
    }
    return (nanos + NANOS_PER_MILLISECOND_MINUS_ONE) / NANOS_PER_MILLISECOND;
  }

  private static AiUnavailableException deadlineExceeded() {
    return new AiUnavailableException(
        "AI_DEADLINE_EXCEEDED", "No time remained to consult the AI service.",
        currentFailureOrigin());
  }
}
