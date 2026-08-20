package io.ramals.learningplatform.ai;

/**
 * The AI plane could not serve this request.
 *
 * <p>Covers both reasons that can be true: the call was attempted and failed (a refused connection,
 * a read timeout, an authentication rejection, an unusable response), or it was never attempted
 * because the deadline had passed or the AI plane is not configured. {@link #code()} says which.
 *
 * <p>It does <em>not</em> distinguish a refusal produced by {@link AiCallGuard} itself — that is
 * {@link AiCallGuard.GuardRefusal}, a subclass the guard alone can raise. The distinction matters
 * because the breaker must count dependency failures and must not count its own refusals; an earlier
 * version drew the line at this class and so counted neither, which left the breaker unable to open.
 *
 * <p>Never fatal to a learner's session. Tutoring is an enhancement over the deterministic core;
 * when it is unavailable the learner keeps their assessments, mastery map and recommendations.
 */
public class AiUnavailableException extends RuntimeException {

  private final String code;
  private final FailureOrigin origin;

  /**
   * Creates a failure attributed to the dependency.
   *
   * <p>{@link FailureOrigin#DEPENDENCY} is the default because the two mistakes are not
   * symmetrical. Counting a caller-side failure degrades availability for a while; failing to count
   * a dependency failure means the breaker never opens at all, which is the defect this class of
   * bug already produced once. A new failure mode is therefore assumed to be about them until
   * someone states otherwise.
   */
  public AiUnavailableException(String code, String message) {
    this(code, message, FailureOrigin.DEPENDENCY);
  }

  public AiUnavailableException(String code, String message, FailureOrigin origin) {
    super(message);
    this.code = code;
    this.origin = origin;
  }

  public String code() {
    return code;
  }

  /** Who this failure is evidence about. Drives circuit-breaker accounting. */
  public FailureOrigin origin() {
    return origin;
  }
}
