package io.ramals.learningplatform.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit breaker and bulkhead for the AI call path.
 *
 * <p>The acceptance criterion for M1-T08 is that {@code ramals-ai} being down does not block
 * deterministic learning. Two different failure modes threaten that, and they need different
 * answers.
 *
 * <p><b>A dead dependency</b> is handled by the breaker. Without one, every learner request waits
 * the full deadline before failing, and the platform spends its request threads discovering
 * something it already knew.
 *
 * <p><b>A slow dependency</b> is handled by the bulkhead, and is the more dangerous of the two. A
 * service that answers in twelve seconds instead of two never trips a breaker, but it will happily
 * consume every thread available and take the deterministic endpoints down with it. Capping
 * concurrent AI calls means the worst case is that tutoring degrades while everything else keeps
 * serving.
 *
 * <p>Written by hand rather than taken from a resilience library. The semantics needed here are
 * narrow, the implementation is small enough to read in one sitting, and — decisively — the clock is
 * injectable, so the state machine can be driven deterministically in tests instead of being coaxed
 * with sleeps. A guard whose behaviour can only be observed by waiting is a guard whose behaviour is
 * mostly assumed.
 */
public class AiCallGuard {

  private static final Logger LOGGER = LoggerFactory.getLogger(AiCallGuard.class);

  /** Breaker states, in the order a failing dependency moves through them. */
  public enum State {
    /** Calls pass through. */
    CLOSED,
    /** Calls are refused without being attempted. */
    OPEN,
    /** One probe is allowed through to find out whether the dependency recovered. */
    HALF_OPEN
  }

  private final int failureThreshold;
  private final Duration openDuration;
  private final Semaphore concurrency;
  private final Supplier<Instant> clock;

  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicReference<Instant> openedAt = new AtomicReference<>(Instant.EPOCH);

  public AiCallGuard(
      int failureThreshold, Duration openDuration, int maxConcurrentCalls, Supplier<Instant> clock) {
    this.failureThreshold = failureThreshold;
    this.openDuration = openDuration;
    // Fair: a learner whose request arrived first should not be starved by later arrivals. Under
    // saturation the difference between fair and unfair queueing is the difference between "slow"
    // and "some requests never complete".
    this.concurrency = new Semaphore(maxConcurrentCalls, true);
    this.clock = clock;
  }

  public State state() {
    // Reading the state also lets an elapsed open window transition to HALF_OPEN, so a caller that
    // only ever inspects still sees an accurate breaker rather than a stale OPEN.
    return currentState();
  }

  public int availablePermits() {
    return concurrency.availablePermits();
  }

  /**
   * Runs the call if the breaker allows it and a bulkhead permit is free.
   *
   * @throws AiUnavailableException when the breaker is open or the bulkhead is saturated — in both
   *     cases without attempting the call, which is the entire point.
   */
  public <T> T call(Supplier<T> action) {
    State current = currentState();
    if (current == State.OPEN) {
      throw new AiUnavailableException("AI_CIRCUIT_OPEN",
          "The tutoring service is unavailable; deterministic learning is unaffected.");
    }

    if (!concurrency.tryAcquire()) {
      // Refused immediately rather than queued. A learner waiting behind a saturated bulkhead is
      // waiting for something that is already overloaded, and the honest answer is now.
      throw new AiUnavailableException("AI_BULKHEAD_FULL",
          "The tutoring service is busy; deterministic learning is unaffected.");
    }

    try {
      T result = action.get();
      recordSuccess();
      return result;
    } catch (RuntimeException failure) {
      recordFailure(failure);
      throw failure;
    } finally {
      concurrency.release();
    }
  }

  private State currentState() {
    State current = state.get();
    if (current != State.OPEN) {
      return current;
    }
    if (clock.get().isBefore(openedAt.get().plus(openDuration))) {
      return State.OPEN;
    }
    // The window has elapsed. Move to HALF_OPEN so exactly one probe is attempted; if it fails the
    // breaker opens again without a storm of retries behind it.
    return state.compareAndSet(State.OPEN, State.HALF_OPEN) ? State.HALF_OPEN : state.get();
  }

  private void recordSuccess() {
    consecutiveFailures.set(0);
    State previous = state.getAndSet(State.CLOSED);
    if (previous != State.CLOSED) {
      LOGGER.atInfo()
          .addKeyValue("operation", "ai.circuit.closed")
          .addKeyValue("previousState", previous)
          .log("AI circuit closed after a successful probe");
    }
  }

  private void recordFailure(RuntimeException failure) {
    // A refusal this guard produced is not evidence about the dependency. Counting it would let a
    // saturated bulkhead trip the breaker, turning a busy service into an unavailable one.
    if (failure instanceof AiUnavailableException) {
      return;
    }

    int failures = consecutiveFailures.incrementAndGet();
    if (failures < failureThreshold && state.get() != State.HALF_OPEN) {
      return;
    }

    state.set(State.OPEN);
    openedAt.set(clock.get());
    LOGGER.atWarn()
        .addKeyValue("operation", "ai.circuit.opened")
        .addKeyValue("consecutiveFailures", failures)
        .addKeyValue("errorType", failure.getClass().getSimpleName())
        .log("AI circuit opened; tutoring degrades and deterministic learning continues");
  }
}
