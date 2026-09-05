package io.ramals.learningplatform.assessment;

/**
 * H5 (M2-ADR-026): how strongly the {@code SUPPORTING}/{@code CONTRADICTORY} evidence gathered so
 * far for one hypothesis tuple (learner + source objective + target objective + relationship type)
 * agrees with itself in favor of that hypothesis -- never a probability, never a claim about ground
 * truth, and never itself a {@code confirmedRootCause} state (no such state exists anywhere in this
 * codebase; this class introduces none). See {@link DiagnosticConfidenceCalculatorV1} for the
 * deterministic rule that produces one of these four values.
 */
public enum DiagnosticConfidenceBand {

  /** No {@code SUPPORTING} or {@code CONTRADICTORY} evidence exists yet for this hypothesis tuple
   * -- including the case where every observation so far was {@code INCONCLUSIVE}. A hypothesis
   * nobody has probed yet is categorically different from one actively contradicted; this is never
   * collapsed into {@link #LOW}. */
  INSUFFICIENT_EVIDENCE,

  /** Evidence exists but does not yet earn more: a single, uncorroborated supporting observation; a
   * balanced or contradiction-dominant history; or a support-dominant history too thin to clear the
   * {@link DiagnosticConfidenceCalculatorV1} thresholds. The floor for every case that is neither
   * unjudged nor genuinely corroborated. */
  LOW,

  /** Real net corroboration -- either two uncontested supporting observations, or a support-dominant
   * mixed history with a substantial net margin that has not (yet) reached the 3-to-1 dominance
   * {@link #HIGH} requires. */
  MODERATE,

  /** Strong corroboration: either three or more uncontested supporting observations, or a mixed
   * history where supporting observations outnumber contradictory ones by more than 3-to-1.
   * Reserved for evidence that is, in substance, unanimous or nearly so -- not merely net-favorable. */
  HIGH
}
