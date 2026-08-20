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

  @Override
  public org.springframework.http.client.ClientHttpRequest createRequest(
      URI uri, HttpMethod httpMethod) throws IOException {
    requireRemaining();
    return super.createRequest(uri, httpMethod);
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
        "AI_DEADLINE_EXCEEDED", "No time remained to consult the AI service.");
  }
}
