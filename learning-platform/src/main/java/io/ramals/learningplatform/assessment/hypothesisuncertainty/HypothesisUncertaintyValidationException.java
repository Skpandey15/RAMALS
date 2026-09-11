package io.ramals.learningplatform.assessment.hypothesisuncertainty;

/**
 * A {@link HypothesisUncertaintyContext} was refused by {@link HypothesisUncertaintyCalculatorV1}'s
 * deterministic input validation (M2-ADR-034 Amendment 1 §Q). Carries exactly one {@link
 * HypothesisUncertaintyReasonCode} -- the stable contract a caller branches on. Fail-closed: a
 * refusal never partially computes a result.
 */
public class HypothesisUncertaintyValidationException extends RuntimeException {

  private final transient HypothesisUncertaintyReasonCode reasonCode;

  public HypothesisUncertaintyValidationException(HypothesisUncertaintyReasonCode reasonCode) {
    super("hypothesis uncertainty context refused: " + reasonCode);
    this.reasonCode = reasonCode;
  }

  public HypothesisUncertaintyReasonCode reasonCode() {
    return reasonCode;
  }
}
