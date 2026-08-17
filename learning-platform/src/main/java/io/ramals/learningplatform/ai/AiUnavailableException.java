package io.ramals.learningplatform.ai;

/**
 * The AI plane could not serve this request, and the platform declined to wait to find out again.
 *
 * <p>Deliberately distinct from a failure of the call itself. This means the guard refused before
 * attempting anything — the breaker was open, or the bulkhead was saturated — which is a different
 * fact for an operator and a different message for a learner.
 *
 * <p>Never fatal to a learner's session. Tutoring is an enhancement over the deterministic core;
 * when it is unavailable the learner keeps their assessments, mastery map and recommendations.
 */
public class AiUnavailableException extends RuntimeException {

  private final String code;

  public AiUnavailableException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
