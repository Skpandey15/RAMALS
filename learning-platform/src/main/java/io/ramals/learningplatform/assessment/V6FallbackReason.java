package io.ramals.learningplatform.assessment;

/**
 * M2-ADR-034 Amendment 3: the deterministic, enumerated reason
 * {@link HypothesisDiscriminationDiagnosticSelector} declined to override {@code
 * DIAGNOSTIC_SELECTION_V5}'s own choice for a given attempt. Every value corresponds to an
 * *expected* control outcome (Amendment 3 §N) -- none of these is an error; each one resolves to
 * "use `V5`'s exact existing selection," never a fabricated distribution or a failed attempt
 * creation.
 */
public enum V6FallbackReason {

  /** No immediately preceding completed source attempt exists (Amendment 3 §C; activation
   * condition 1). */
  NO_SOURCE_ATTEMPT,

  /** No relationship-authorized hypothesis is `V6`-actionable for this destination attempt --
   * every one that resolved {@code CANDIDATES_AVAILABLE} had zero candidates surviving destination
   * eligibility, or nothing resolved at all (Amendment 3 §H/§I; activation condition 2). */
  NO_ACTIONABLE_HYPOTHESES,

  /** The `V6`-actionable working set's combined candidate-probe count, across every admitted
   * hypothesis, is exactly one -- there is only one possible action, so ranking cannot change the
   * outcome. Step 1/Step 2 are not invoked for this case (Amendment 3 §P). */
  SINGLE_CANDIDATE_TOTAL,

  /** {@code HYPOTHESIS_UNCERTAINTY_V1} returned {@code NOT_APPLICABLE} (Amendment 3 §L; activation
   * condition 4). Structurally unreachable once {@link #NO_ACTIONABLE_HYPOTHESES} has already been
   * ruled out, since {@code NOT_APPLICABLE} requires an empty candidate set -- retained as
   * defense-in-depth. */
  STEP1_NOT_APPLICABLE,

  /** {@code HYPOTHESIS_UNCERTAINTY_V1} returned {@code INSUFFICIENT_EVIDENCE} -- every actionable
   * hypothesis lacks directional evidence in the source interaction, the common case for a freshly
   * authorized working set (Amendment 3 §M; activation condition 4). */
  STEP1_INSUFFICIENT_EVIDENCE,

  /** Fewer than two hypotheses participate in the Step-1 result. A sole participant normalizes to
   * {@code 1.0000} in every reachable world (Amendment 2 §H), so discrimination is mathematically
   * certain to be a no-op (Amendment 3 §J; activation condition 5). */
  FEWER_THAN_TWO_PARTICIPANTS,

  /** {@code HYPOTHESIS_DISCRIMINATION_V1} returned {@code NOT_APPLICABLE} (Amendment 3 §J;
   * activation condition 6). Structurally unreachable once Step 1 is confirmed {@code APPLICABLE}
   * with >=2 participants -- retained as defense-in-depth. */
  STEP2_NOT_APPLICABLE,

  /** Every candidate probe scored exactly {@code 0.0000} -- a valid, non-error {@code SCORABLE}
   * result that carries no separating power (Amendment 3 §K; activation condition 7). */
  ALL_SCORES_ZERO
}
