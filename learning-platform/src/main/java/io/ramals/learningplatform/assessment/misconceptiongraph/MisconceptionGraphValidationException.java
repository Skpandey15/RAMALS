package io.ramals.learningplatform.assessment.misconceptiongraph;

/**
 * A misconception graph edge was refused by deterministic validation. Carries exactly one
 * {@link MisconceptionRelationshipReasonCode} -- the stable contract a caller branches on
 * (M2-ADR-033 §13). Fail-closed: a refusal never partially persists.
 */
public class MisconceptionGraphValidationException extends RuntimeException {

  private final transient MisconceptionRelationshipReasonCode reasonCode;

  public MisconceptionGraphValidationException(MisconceptionRelationshipReasonCode reasonCode) {
    super("misconception graph edge refused: " + reasonCode);
    this.reasonCode = reasonCode;
  }

  public MisconceptionRelationshipReasonCode reasonCode() {
    return reasonCode;
  }
}
