package io.ramals.learningplatform.ai;

/**
 * Who a failed AI call is evidence about.
 *
 * <p>The circuit breaker exists to stop asking a dependency that is not answering, so only failures
 * caused by the dependency may count towards opening it. Deciding that from the error code does not
 * work, because one code covers more than one origin: {@code AI_DEADLINE_EXCEEDED} is produced both
 * when the caller's budget ran out before anything was sent <em>and</em> when the AI plane was
 * contacted and failed to answer in time. Those are opposite facts about the dependency's health and
 * they are indistinguishable by string.
 *
 * <p>So origin is carried explicitly, and for the deadline cases it is derived from something
 * observed rather than assumed — {@link DeadlineAwareClientHttpRequestFactory#dispatchAttempted()}
 * records whether a request was actually started.
 */
public enum FailureOrigin {

  /**
   * This side gave up before the dependency was involved.
   *
   * <p>A budget that expired before dispatch, or an AI plane that is not configured. Says nothing
   * about whether the dependency is healthy, because it was never asked.
   */
  CALLER,

  /**
   * {@link AiCallGuard} refused before attempting the call.
   *
   * <p>An open breaker or a saturated bulkhead. Counting these would let the guard's own refusals
   * feed the state that produces them.
   */
  GUARD,

  /**
   * The dependency was contacted and the call failed.
   *
   * <p>Refused connection, connect or read timeout, authentication rejection, unusable response, or
   * a reply that arrived after the budget. A slow dependency lands here on purpose: Doc 01 calls it
   * the more dangerous failure mode, and a breaker that cannot see it protects nothing.
   */
  DEPENDENCY
}
