package io.ramals.learningplatform.ai;

/**
 * Why a tutor response is absent.
 *
 * <p>The distinction this enum draws is the point of it. "Tutoring is switched off in this
 * environment" and "the AI plane is broken right now" produce the same learner experience and are
 * completely different operational facts. Collapsing them into an empty result would mean an outage
 * is indistinguishable from a configuration choice — and the outage would be invisible until
 * somebody noticed the tutor had been quiet for a week.
 *
 * <p>{@link #expected()} is what separates them: expected reasons are ordinary states, operational
 * reasons are things somebody may need to act on.
 */
public enum TutorUnavailableReason {

  /** No AI plane is configured here. Ordinary in local development and in a deterministic-only
   *  deployment; never an incident. */
  NOT_CONFIGURED(true, "AI_NOT_CONFIGURED"),

  /** The request named a skill the platform does not define, so nothing was sent. A caller bug or
   *  a stale link, not an AI failure. */
  UNKNOWN_SKILL(true, "UNKNOWN_SKILL"),

  /** The breaker is open: the AI plane failed repeatedly and is not being retried yet. */
  CIRCUIT_OPEN(false, "AI_CIRCUIT_OPEN"),

  /** The bulkhead is saturated. The AI plane is up but slower than the load arriving. */
  BUSY(false, "AI_BULKHEAD_FULL"),

  /** The AI plane could not be reached, or answered in a way we could not use. */
  TRANSPORT_FAILURE(false, "AI_TRANSPORT_FAILURE"),

  /** No time remained in the caller's budget to consult the AI plane. */
  DEADLINE_EXCEEDED(false, "AI_DEADLINE_EXCEEDED");

  private final boolean expected;
  private final String code;

  TutorUnavailableReason(boolean expected, String code) {
    this.expected = expected;
    this.code = code;
  }

  /**
   * Whether this is an ordinary state rather than something to investigate.
   *
   * <p>Drives log level and alerting. Logging a configuration choice at WARN trains an operator to
   * ignore the level, which costs them the one occurrence that mattered.
   */
  public boolean expected() {
    return expected;
  }

  /** Stable code for metrics, logs and problem responses. */
  public String code() {
    return code;
  }

  static TutorUnavailableReason fromCode(String code) {
    for (TutorUnavailableReason reason : values()) {
      if (reason.code.equals(code)) {
        return reason;
      }
    }
    // An unrecognised code is treated as an operational failure rather than an expected one. The
    // safe default is the one that stays visible.
    return TRANSPORT_FAILURE;
  }
}
