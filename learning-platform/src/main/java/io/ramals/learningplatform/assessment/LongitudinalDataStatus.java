package io.ramals.learningplatform.assessment;

/**
 * M2-ADR-030 (H7): whether a governed baseline evidentiary boundary exists at all for one {@code
 * (learner, misconception)} pair -- distinct from {@link LongitudinalEvidenceState}, which classifies
 * what post-baseline evidence says once a baseline exists. {@code NO_BASELINE} is never conflated
 * with {@link LongitudinalEvidenceState#NO_LATER_EVIDENCE}: the former means H7 has no fixed boundary
 * to measure from at all; the latter is itself a real classification, only meaningful once a baseline
 * already exists and no evidence has arrived after it.
 */
public enum LongitudinalDataStatus {

  /** No persisted {@code core.misconception_confidence_observation} row exists yet for this pair
   * with {@code supporting_count + contradictory_count > 0} -- no eligible baseline, so H7 has
   * nothing to classify. Never manufactured as a synthetic {@link LongitudinalEvidenceState}. */
  NO_BASELINE,

  /** The earliest such eligible snapshot exists and is fixed as this pair's permanent baseline;
   * {@link LongitudinalEvidenceState} classifies the evidence recorded after it. */
  HAS_BASELINE
}
