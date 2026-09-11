package io.ramals.learningplatform.assessment.hypothesisuncertainty;

/**
 * M2-ADR-034 Amendment 1 §D: whether {@code HYPOTHESIS_UNCERTAINTY_V1} produced a distribution, and
 * why not when it did not. Evidence sufficiency is kept separate from relative belief on purpose --
 * neither absence of a candidate nor absence of evidence is ever expressed as a numeric value.
 */
public enum HypothesisUncertaintyStatus {

  /** The candidate hypothesis set was empty. {@code candidates} is empty; there is no distribution. */
  NOT_APPLICABLE,

  /** Candidates exist, but none has directional ({@code SUPPORTING} or {@code CONTRADICTORY})
   * evidence in this interaction -- every candidate's band is {@code INSUFFICIENT_EVIDENCE}. Every
   * candidate is returned with {@code participates = false} and {@code normalizedValue = null}; no
   * distribution is manufactured from absent evidence. */
  INSUFFICIENT_EVIDENCE,

  /** At least one candidate has directional evidence. The distribution is normalized over exactly
   * that participating subset; a candidate whose own band is {@code INSUFFICIENT_EVIDENCE} is still
   * returned, but with {@code participates = false} and {@code normalizedValue = null} -- represented,
   * never scored (Amendment 1 §D/§J Case D). */
  APPLICABLE
}
