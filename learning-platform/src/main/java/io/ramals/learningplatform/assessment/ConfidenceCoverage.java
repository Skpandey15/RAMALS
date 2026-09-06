package io.ramals.learningplatform.assessment;

/**
 * M2-ADR-030 (H7): whether the latest persisted {@code core.misconception_confidence_observation}
 * snapshot for one {@code (learner, misconception)} pair's own cited provenance covers every {@code
 * core.misconception_evidence_observation} row that exists for that pair -- determined strictly by
 * exact evidence-observation-id provenance, never by comparing timestamps, and never by H7
 * recomputing G3 itself.
 *
 * <p>Meaningful only when a persisted G3 snapshot exists at all; {@code null} (not a value of this
 * enum) when {@code latestConfidence} is absent -- never reported as {@code CURRENT} in that case.
 */
public enum ConfidenceCoverage {

  /** Every evidence row for the pair is cited in the latest snapshot's own provenance -- the
   * ordinary case, since {@code MisconceptionConfidenceService.recomputeForAttempt} runs
   * synchronously and unconditionally in the same transaction as G2 capture (M2-ADR-028 §4). */
  CURRENT,

  /** At least one evidence row for the pair is not cited by the latest snapshot's own provenance --
   * should not arise under correct current orchestration, but computed defensively by exact
   * provenance rather than assumed, so a future defect or manual data correction is never silently
   * misreported as {@code CURRENT}. */
  STALE_RELATIVE_TO_LATER_EVIDENCE
}
