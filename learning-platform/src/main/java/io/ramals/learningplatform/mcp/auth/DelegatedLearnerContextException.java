package io.ramals.learningplatform.mcp.auth;

/**
 * Raised when a delegated learner-context token fails validation (M2-ADR-031). Carries a stable
 * {@link Reason} rather than a free-text message an MCP client could parse and depend on -- and
 * never carries the raw token, its signature, or any claim value, so it can be logged directly
 * without a redaction step at every call site.
 */
public class DelegatedLearnerContextException extends RuntimeException {

  /** Stable, non-leaking failure reasons. Never mapped 1:1 to a client-visible message that quotes
   * claim contents -- see {@code DelegatedLearnerContextValidator}'s own javadoc for what is safe to
   * disclose. */
  public enum Reason {
    MISSING,
    MALFORMED,
    BAD_SIGNATURE,
    WRONG_ISSUER,
    WRONG_AUDIENCE,
    EXPIRED,
    NOT_YET_VALID,
    UNKNOWN_KEY_ID,
    MISSING_INTERACTION_ID,
    MISSING_LEARNER_SCOPE,
    MISSING_DOMAIN_SCOPE,
    MISSING_CAPABILITIES,
    INVALID_CAPABILITY_FORMAT
  }

  private final Reason reason;

  public DelegatedLearnerContextException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
