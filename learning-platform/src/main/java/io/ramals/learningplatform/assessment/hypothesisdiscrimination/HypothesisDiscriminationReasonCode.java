package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

/**
 * M2-ADR-034 Amendment 2 §N: the stable, typed reasons {@code HYPOTHESIS_DISCRIMINATION_V1} fails
 * closed on an invalid {@link HypothesisDiscriminationContext}, rather than repairing or silently
 * normalizing malformed authoritative input. A valid empty state ({@link
 * HypothesisDiscriminationStatus#NOT_APPLICABLE}, or {@link HypothesisDiscriminationStatus#SCORABLE}
 * with {@code probes = []}) is never one of these -- it is a successful result, not a refusal.
 */
public enum HypothesisDiscriminationReasonCode {

  /** {@code baseResult} does not equal {@code HYPOTHESIS_UNCERTAINTY_V1.calculate(baseContext)}
   * exactly -- this amendment never accepts a hand-constructed or stale result paired with a
   * different context. */
  BASE_RESULT_MISMATCH,

  /** A candidate probe's {@code hypothesis} does not match any hypothesis in {@code
   * baseContext.candidates()}. */
  PROBE_FOR_UNKNOWN_HYPOTHESIS,

  /** A candidate probe is missing {@code probeItemVersionId} or {@code hypothesis}. */
  MALFORMED_CANDIDATE_PROBE,

  /** The same {@code (probeItemVersionId, hypothesis)} pair appears twice in {@code candidates}. */
  DUPLICATE_CANDIDATE_PROBE,

  /** A candidate probe's own domain (via its {@code hypothesis}) differs from {@code
   * baseContext.domainCode()}. Structurally unreachable through {@link
   * HypothesisDiscriminationCalculatorV1#calculate}'s public API: {@code BASE_RESULT_MISMATCH} is
   * checked first, and a {@code baseContext} that could ever produce a matching {@code baseResult}
   * has already had every one of its own candidates domain-checked by {@code
   * HYPOTHESIS_UNCERTAINTY_V1}'s own {@code CROSS_DOMAIN_CANDIDATE_SET} validation. This reason
   * exists as documented defense-in-depth -- the same "second independent enforcement layer"
   * precedent Amendment 1 §Q's {@code NEGATIVE_EVIDENCE_COUNT} already sets, which likewise has no
   * reachable test of its own. */
  CROSS_DOMAIN_CANDIDATE_PROBE
}
