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
   * Whether a request was actually started in the current scope.
   *
   * <p>This is the fact that separates "we ran out of time before asking" from "we asked and they
   * did not answer". Both surface as {@code AI_DEADLINE_EXCEEDED}, and only the second is evidence
   * about the dependency's health, so the distinction cannot be recovered from the error code and
   * must be recorded when it happens.
   */
  private static final ThreadLocal<Boolean> DISPATCH_ATTEMPTED = new ThreadLocal<>();

  @Override
  public org.springframework.http.client.ClientHttpRequest createRequest(
      URI uri, HttpMethod httpMethod) throws IOException {
    requireRemaining();
    org.springframework.http.client.ClientHttpRequest request =
        super.createRequest(uri, httpMethod);
    // Recorded here rather than after a successful response: from this point the dependency is
    // being contacted, so a connect timeout, a read timeout or a late reply are all about it.
    DISPATCH_ATTEMPTED.set(Boolean.TRUE);
    return request;
  }

  /** Whether the current scope has started a request to the dependency. */
  static boolean dispatchAttempted() {
    return Boolean.TRUE.equals(DISPATCH_ATTEMPTED.get());
  }

  /**
   * The origin to attribute a failure to, given whether the dependency has been contacted yet.
   *
   * <p>Derived from an observed fact rather than from the error code, which cannot tell the two
   * apart.
   */
  static FailureOrigin currentFailureOrigin() {
    return dispatchAttempted() ? FailureOrigin.DEPENDENCY : FailureOrigin.CALLER;
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
    Boolean previousDispatch = DISPATCH_ATTEMPTED.get();
    DISPATCH_ATTEMPTED.remove();
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
      // An inner scope's dispatch does not make an outer scope's failure a dependency failure:
      // acquiring a workload token nests inside the AI call, and a token that was fetched says
      // nothing about whether the AI plane was reached.
      if (previousDispatch == null) {
        DISPATCH_ATTEMPTED.remove();
      } else {
        DISPATCH_ATTEMPTED.set(previousDispatch);
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
