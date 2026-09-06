package io.ramals.learningplatform.assessment;

/**
 * M2-ADR-030 (H7): what {@code core.misconception_evidence_observation} rows recorded strictly after
 * a fixed baseline evidentiary boundary say about one authored misconception -- never a claim about
 * the baseline's own direction, never verification/confirmation/resolution/recurrence/regression of
 * anything. Produced only by {@link LongitudinalEvidencePolicyV1}, a pure sign/existence classifier
 * over post-baseline {@code SUPPORTING}/{@code CONTRADICTORY}/{@code INCONCLUSIVE} counts -- order-
 * independent, with no numeric threshold and no probability.
 *
 * <p>Only meaningful when {@link LongitudinalDataStatus#HAS_BASELINE}; never populated when {@link
 * LongitudinalDataStatus#NO_BASELINE} (there is nothing to classify yet).
 */
public enum LongitudinalEvidenceState {

  /** No {@code core.misconception_evidence_observation} row exists for this pair outside the
   * baseline's own cited provenance set. */
  NO_LATER_EVIDENCE,

  /** Later evidence exists, but every row is {@code INCONCLUSIVE} -- present, but directionless. */
  LATER_INCONCLUSIVE_ONLY,

  /** Later evidence contains at least one {@code SUPPORTING} row and no {@code CONTRADICTORY} row
   * (any number of {@code INCONCLUSIVE} rows alongside). Says nothing about the baseline's own
   * direction -- the same label applies whether the baseline itself was supporting, contradictory, or
   * already mixed. */
  LATER_SUPPORT_ONLY,

  /** Later evidence contains at least one {@code CONTRADICTORY} row and no {@code SUPPORTING} row
   * (any number of {@code INCONCLUSIVE} rows alongside). Never worded as "resolved" or "cured". */
  LATER_CONTRADICTION_ONLY,

  /** Later evidence contains at least one {@code SUPPORTING} and at least one {@code CONTRADICTORY}
   * row. Never worded as "recurrence" -- this classifier is order-independent and draws no sequential
   * inference from which arrived first. */
  LATER_MIXED_EVIDENCE
}
