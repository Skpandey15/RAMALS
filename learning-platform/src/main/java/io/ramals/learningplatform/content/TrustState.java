package io.ramals.learningplatform.content;

/**
 * Trust state of assessment content (Doc 03 §4, M1-ADR-006).
 *
 * <p>Only {@link #VERIFIED_CONTENT} may be served where answers become evidence. The other two are
 * both "not usable", but for different reasons a reviewer and an operator need to tell apart.
 */
public enum TrustState {

  /** Freshly created and not yet through the pipeline. The state everything starts in. */
  UNVERIFIED,

  /** Passed the pipeline and received the approval the policy required. */
  VERIFIED_CONTENT,

  /** Refused by a pipeline stage. The stage that refused is recorded alongside. */
  REJECTED;

  public boolean usableInScoredContext() {
    return this == VERIFIED_CONTENT;
  }
}
