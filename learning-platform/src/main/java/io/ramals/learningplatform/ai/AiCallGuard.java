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
 *
 * <p><b>Resilience4j is the preferred long-term home for this.</b> The decision to keep a custom
 * implementation is about not adding a dependency for its own sake while the requirements are this
 * small, not a judgement that a library would be worse. Replace it when any of these become true:
 * more than one call path needs guarding, retry or rate-limiting joins the picture, or the
 * configuration wants to live in properties rather than in code. The tests in
 * {@code AiCallGuardTests} assert behaviour rather than internals, so they should survive the swap
 * and are the thing that makes it safe.
 */
public class AiCallGuard {

  private static final Logger LOGGER = LoggerFactory.getLogger(AiCallGuard.class);

  /**
   * A refusal this guard produced, as opposed to a failure of the dependency behind it.
   *
   * <p>Private constructor by design: only the guard can raise one, so "the guard refused" and "the
   * AI plane failed" cannot be confused by a caller wrapping its own errors. The distinction decides
   * whether the breaker counts a failure, and getting it wrong in the permissive direction disables
   * the breaker without breaking a single test.
   *
   * <p>It remains an {@link AiUnavailableException} carrying the same error codes, so every existing
   * caller and degradation path is unchanged.
   */
  public static final class GuardRefusal extends AiUnavailableException {
    private GuardRefusal(String errorCode, String message) {
      super(errorCode, message);
    }
  }

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
      throw new GuardRefusal("AI_CIRCUIT_OPEN",
          "The tutoring service is unavailable; deterministic learning is unaffected.");
    }

    if (!concurrency.tryAcquire()) {
      // Refused immediately rather than queued. A learner waiting behind a saturated bulkhead is
      // waiting for something that is already overloaded, and the honest answer is now.
      throw new GuardRefusal("AI_BULKHEAD_FULL",
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
    //
    // Matched on the guard's own type rather than on AiUnavailableException, which is what this
    // originally tested. Every client wraps a genuine transport failure — a refused connection, a
    // read timeout, a 401 — in an AiUnavailableException before it leaves the lambda, so the wider
    // check silently excluded exactly the failures the breaker exists to count, and the breaker
    // could never open. A caller cannot construct a GuardRefusal, so the exclusion now covers what
    // it is meant to cover and nothing else.
    if (failure instanceof GuardRefusal) {
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
