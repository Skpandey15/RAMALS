package io.ramals.learningplatform.execution.contractb;

/**
 * The provider refused because it is being asked too often (M2-ADR-020 §7).
 *
 * <p>Distinct from an outage, and the distinction is the point: they call for opposite responses. An
 * unavailable provider may be retried on the same cadence — the next attempt costs nothing anyone
 * else needs. A rate limit means the next attempt is guaranteed to fail and to consume quota that
 * every other execution is also waiting for, so retrying at the same cadence makes recovery slower
 * for everyone, not faster for this one.
 *
 * <p>Before this existed both arrived at the recovery path as an indistinguishable
 * {@code RuntimeException} and were retried identically, thirty seconds apart. That is how W2's
 * enumeration reached 429 and stayed there.
 *
 * <p>Never terminal on its own. A rate limit says nothing whatsoever about whether an orphan exists,
 * so it can no more end an execution than a dropped connection can. It only changes <em>when</em> the
 * next attempt happens.
 *
 * @param retryAfterMillis what the provider asked for, or null if it did not say
 */
public class DurableExecutionRateLimitedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Long retryAfterMillis;

  public DurableExecutionRateLimitedException(String detail, Long retryAfterMillis) {
    super(detail);
    this.retryAfterMillis = retryAfterMillis;
  }

  /**
   * How long the provider asked the caller to wait, or null.
   *
   * <p>Honoured in preference to the caller's own backoff when present, because the provider knows
   * when it will serve again and RAMALS is guessing. Clamped by the caller: an unbounded
   * provider-supplied delay should not be able to push a recovery past its horizon in one step.
   */
  public Long retryAfterMillis() {
    return retryAfterMillis;
  }
}
