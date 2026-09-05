package io.ramals.learningplatform.assessment;

/**
 * H5 (M2-ADR-023 §2): every distinct evidence observation gathered so far for one hypothesis tuple,
 * already classified by {@link HypothesisEvidenceOutcome} and reduced to counts -- the only shape
 * {@link DiagnosticConfidenceCalculatorV1} ever reads. {@code inconclusiveCount} is carried for
 * audit completeness only; per {@link HypothesisEvidenceOutcome}, it never contributes to or blocks
 * a band (see the calculator's own javadoc).
 *
 * @param supportingCount distinct observations classified {@link HypothesisEvidenceOutcome#SUPPORTING}
 * @param contradictoryCount distinct observations classified {@link HypothesisEvidenceOutcome#CONTRADICTORY}
 * @param inconclusiveCount distinct observations classified {@link HypothesisEvidenceOutcome#INCONCLUSIVE}
 */
public record DiagnosticConfidenceInputs(
    int supportingCount, int contradictoryCount, int inconclusiveCount) {

  public DiagnosticConfidenceInputs {
    if (supportingCount < 0 || contradictoryCount < 0 || inconclusiveCount < 0) {
      throw new IllegalArgumentException("Evidence counts cannot be negative.");
    }
  }
}
