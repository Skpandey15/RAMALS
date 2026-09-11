package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

/**
 * M2-ADR-034 Amendment 2 §G: whether {@code HYPOTHESIS_DISCRIMINATION_V1} produced scores for the
 * supplied candidate probes, and why not when it did not.
 */
public enum HypothesisDiscriminationStatus {

  /** {@code baseResult.status()} is {@code NOT_APPLICABLE} or {@code INSUFFICIENT_EVIDENCE}: there
   * is no participating uncertainty mass to separate, so "how much of the current mass would this
   * probe separate" is undefined -- not zero, undefined. No score is manufactured; {@code probes}
   * is empty. */
  NOT_APPLICABLE,

  /** {@code baseResult.status()} is {@code APPLICABLE}. Every supplied candidate probe is scored;
   * zero supplied candidate probes is a valid, successful result with {@code probes = []}. */
  SCORABLE
}
