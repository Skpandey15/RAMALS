package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

/**
 * A {@link HypothesisDiscriminationContext} was refused by {@link
 * HypothesisDiscriminationCalculatorV1}'s deterministic input validation (M2-ADR-034 Amendment 2
 * §N). Carries exactly one {@link HypothesisDiscriminationReasonCode} -- the stable contract a
 * caller branches on. Fail-closed: a refusal never partially computes a result.
 */
public class HypothesisDiscriminationValidationException extends RuntimeException {

  private final transient HypothesisDiscriminationReasonCode reasonCode;

  public HypothesisDiscriminationValidationException(HypothesisDiscriminationReasonCode reasonCode) {
    super("hypothesis discrimination context refused: " + reasonCode);
    this.reasonCode = reasonCode;
  }

  public HypothesisDiscriminationReasonCode reasonCode() {
    return reasonCode;
  }
}
