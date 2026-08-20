package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Supplier;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Circuit breaker and bulkhead behaviour, driven by an injected clock.
 *
 * <p>Time is supplied rather than waited for. A guard whose behaviour can only be observed by
 * sleeping is a guard whose behaviour is mostly assumed — and the states that matter here (the open
 * window elapsing, a half-open probe failing) are precisely the ones a sleep-based test would either
 * miss or make flaky.
 */
class AiCallGuardTests {

  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-17T00:00:00Z"));

  private AiCallGuard guard(int failureThreshold, int maxConcurrent) {
    return new AiCallGuard(failureThreshold, Duration.ofSeconds(30), maxConcurrent, now::get);
  }

  private void advance(Duration duration) {
    now.set(now.get().plus(duration));
  }

  /**
   * The failure a real client hands the guard.
   *
   * <p>This used to be an {@code IllegalStateException}, which no client produces. Every client
   * catches {@code RestClientException} and rethrows an {@link AiUnavailableException} before it
   * leaves the guarded lambda, and the guard's failure accounting skipped that type — so the breaker
   * could never open and the tests still passed. Simulating the real shape is what makes these tests
   * about the breaker rather than about a scenario that does not occur.
   */
  private static RuntimeException transportFailure() {
    return new AiUnavailableException(
        "AI_TRANSPORT_FAILURE", "The tutoring service could not be reached.");
  }

  // -- circuit breaker -----------------------------------------------------------------------------

  @Test
  @DisplayName("a healthy dependency keeps the circuit closed")
  void healthyCallsKeepTheCircuitClosed() {
    AiCallGuard guard = guard(2, 4);

    for (int attempt = 0; attempt < 10; attempt++) {
      assertThat(guard.call(() -> "ok")).isEqualTo("ok");
    }
    assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);
  }

  @Test
  @DisplayName("consecutive failures open the circuit")
  void consecutiveFailuresOpenTheCircuit() {
    AiCallGuard guard = guard(2, 4);

    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> guard.call(() -> {
        throw transportFailure();
      })).isInstanceOf(AiUnavailableException.class);
    }

    assertThat(guard.state()).isEqualTo(AiCallGuard.State.OPEN);
  }

  @Test
  @DisplayName("an open circuit refuses without attempting the call")
  void anOpenCircuitDoesNotAttemptTheCall() {
    AiCallGuard guard = guard(1, 4);
    assertThatThrownBy(() -> guard.call(() -> {
      throw transportFailure();
    })).isInstanceOf(AiUnavailableException.class);

    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(() -> guard.call(attempts::incrementAndGet))
        .isInstanceOf(AiUnavailableException.class)
        .extracting(failure -> ((AiUnavailableException) failure).code())
        .isEqualTo("AI_CIRCUIT_OPEN");

    // The whole point: every learner request no longer waits the full deadline to rediscover that
    // the dependency is down.
    assertThat(attempts).hasValue(0);
  }

  @Test
  @DisplayName("a success closes the circuit again after the open window elapses")
  void theCircuitRecovers() {
    AiCallGuard guard = guard(1, 4);
    assertThatThrownBy(() -> guard.call(() -> {
      throw transportFailure();
    })).isInstanceOf(AiUnavailableException.class);
    assertThat(guard.state()).isEqualTo(AiCallGuard.State.OPEN);

    advance(Duration.ofSeconds(31));

    assertThat(guard.state()).isEqualTo(AiCallGuard.State.HALF_OPEN);
    assertThat(guard.call(() -> "recovered")).isEqualTo("recovered");
    assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);
  }

  @Test
  @DisplayName("a failed half-open probe re-opens the circuit immediately")
  void aFailedProbeReopensTheCircuit() {
    AiCallGuard guard = guard(3, 4);
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> guard.call(() -> {
        throw transportFailure();
      })).isInstanceOf(AiUnavailableException.class);
    }
    advance(Duration.ofSeconds(31));
    assertThat(guard.state()).isEqualTo(AiCallGuard.State.HALF_OPEN);

    // One failure is enough in HALF_OPEN, without waiting for the threshold again: the probe exists
    // to answer one question, and it answered it.
    assertThatThrownBy(() -> guard.call(() -> {
      throw transportFailure();
    })).isInstanceOf(AiUnavailableException.class);

    assertThat(guard.state()).isEqualTo(AiCallGuard.State.OPEN);
  }

  @Test
  @DisplayName("a success resets the failure count so intermittent errors do not accumulate")
  void successResetsTheFailureCount() {
    AiCallGuard guard = guard(3, 4);

    assertThatThrownBy(() -> guard.call(() -> {
      throw transportFailure();
    })).isInstanceOf(AiUnavailableException.class);
    guard.call(() -> "ok");
    assertThatThrownBy(() -> guard.call(() -> {
      throw transportFailure();
    })).isInstanceOf(AiUnavailableException.class);

    // Two failures separated by a success is not a broken dependency; opening here would make the
    // breaker fire on ordinary noise.
    assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);
  }

  // -- bulkhead -------------------------------------------------------------------------------------

  @Test
  @DisplayName("concurrent calls beyond the limit are refused rather than queued")
  void theBulkheadRefusesExcessConcurrency() throws InterruptedException {
    AiCallGuard guard = guard(5, 1);
    CountDownLatch inFlight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Thread occupant = new Thread(() -> guard.call(() -> {
      inFlight.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return "held";
    }));
    occupant.start();
    assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

    try {
      assertThatThrownBy(() -> guard.call(() -> "second"))
          .isInstanceOf(AiUnavailableException.class)
          .extracting(failure -> ((AiUnavailableException) failure).code())
          .isEqualTo("AI_BULKHEAD_FULL");
    } finally {
      release.countDown();
      occupant.join(5_000);
    }
  }

  @Test
  @DisplayName("a permit is returned even when the call throws")
  void permitsAreReleasedOnFailure() {
    AiCallGuard guard = guard(100, 1);

    assertThatThrownBy(() -> guard.call(() -> {
      throw transportFailure();
    })).isInstanceOf(AiUnavailableException.class);

    // A leaked permit would turn one transport error into a permanently unavailable tutor, which
    // looks exactly like a dependency outage and is not one.
    assertThat(guard.availablePermits()).isEqualTo(1);
    assertThat(guard.call(() -> "ok")).isEqualTo("ok");
  }

  @Test
  @DisplayName("a bulkhead refusal does not count towards opening the circuit")
  void bulkheadRefusalsDoNotTripTheBreaker() throws InterruptedException {
    AiCallGuard guard = guard(2, 1);
    CountDownLatch inFlight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Thread occupant = new Thread(() -> guard.call(() -> {
      inFlight.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return "held";
    }));
    occupant.start();
    assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

    try {
      for (int attempt = 0; attempt < 5; attempt++) {
        assertThatThrownBy(() -> guard.call(() -> "rejected"))
            .isInstanceOf(AiUnavailableException.class);
      }
      // A busy service is not a broken one. Counting our own refusals would let load alone turn
      // "slow" into "unavailable", which is the opposite of what the bulkhead is for.
      assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);
    } finally {
      release.countDown();
      occupant.join(5_000);
    }
  }

  // -- what counts as evidence about the dependency ---------------------------------------------

  @Test
  @DisplayName("a caller's expired deadline does not open the circuit")
  void aCallerDeadlineIsNotEvidenceAboutTheDependency() {
    // AI_DEADLINE_EXCEEDED means this side ran out of budget, possibly before the request was sent.
    // Counting it lets three impatient callers disable tutoring for every learner -- including ones
    // with a full budget -- while the AI plane is answering normally.
    AiCallGuard guard = guard(3, 4);

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> guard.call(() -> {
        throw new AiUnavailableException(
            "AI_DEADLINE_EXCEEDED", "No time remained to consult the tutoring service.");
      })).isInstanceOf(AiUnavailableException.class);
    }

    assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);
  }

  @Test
  @DisplayName("an expired deadline does not mask a real failure that follows it")
  void aDeadlineDoesNotResetTheFailureCount() {
    // The converse risk of the test above: if ignoring a deadline also cleared the counter, a
    // dependency failing intermittently between short-deadline calls would never reach the
    // threshold and the breaker would stay shut on a genuinely sick service.
    AiCallGuard guard = guard(3, 4);

    guardCallIgnoring(guard, () -> {
      throw new AiUnavailableException("AI_TRANSPORT_FAILURE", "unreachable");
    });
    guardCallIgnoring(guard, () -> {
      throw new AiUnavailableException("AI_DEADLINE_EXCEEDED", "out of time");
    });
    guardCallIgnoring(guard, () -> {
      throw new AiUnavailableException("AI_TRANSPORT_FAILURE", "unreachable");
    });
    guardCallIgnoring(guard, () -> {
      throw new AiUnavailableException("AI_TRANSPORT_FAILURE", "unreachable");
    });

    assertThat(guard.state()).isEqualTo(AiCallGuard.State.OPEN);
  }

  private void guardCallIgnoring(AiCallGuard guard, Supplier<Object> action) {
    try {
      guard.call(action);
    } catch (AiUnavailableException ignored) {
      // the caller's degradation path; this test is about the breaker's bookkeeping
    }
  }
}
