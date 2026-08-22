package io.ramals.learningplatform.grounding;

/** Stable fail-closed retrieval error suitable for metrics and audit without leaking internals. */
public final class GroundingRetrievalException extends RuntimeException {
  private final String code;

  public GroundingRetrievalException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
