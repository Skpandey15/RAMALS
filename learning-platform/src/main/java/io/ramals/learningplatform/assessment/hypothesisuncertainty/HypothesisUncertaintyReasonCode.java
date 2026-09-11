package io.ramals.learningplatform.assessment.hypothesisuncertainty;

/**
 * M2-ADR-034 Amendment 1 §Q: the stable, typed reasons {@code HYPOTHESIS_UNCERTAINTY_V1} fails
 * closed on an invalid {@link HypothesisUncertaintyContext}, rather than repairing or silently
 * normalizing malformed authoritative diagnostic data. A valid empty state ({@link
 * HypothesisUncertaintyStatus#NOT_APPLICABLE} / {@link HypothesisUncertaintyStatus#INSUFFICIENT_EVIDENCE})
 * is never one of these -- it is a successful result, not a refusal.
 */
public enum HypothesisUncertaintyReasonCode {

  /** The same hypothesis identity (Amendment 1 §H's five-field key) appears twice in the candidate
   * set. */
  DUPLICATE_HYPOTHESIS,

  /** An evidence input references a hypothesis that is not in the candidate set. */
  EVIDENCE_FOR_UNKNOWN_HYPOTHESIS,

  /** A negative evidence count reached {@code DiagnosticConfidenceInputs}. Structurally unreachable
   * through {@link HypothesisUncertaintyCalculatorV1}'s own counting (a count derived by counting a
   * list can never be negative); this reason exists as the second of the two independent
   * enforcement layers Amendment 1 §Q names ("also enforced by {@code DiagnosticConfidenceInputs}'
   * own constructor"). */
  NEGATIVE_EVIDENCE_COUNT,

  /** An evidence input's {@code interactionId} does not match the context's own -- V1 is
   * interaction-bound (Amendment 1 §F); evidence from a different interaction never participates. */
  EVIDENCE_INTERACTION_MISMATCH,

  /** A candidate's own {@code domainCode} does not match the context's single declared domain. */
  CROSS_DOMAIN_CANDIDATE_SET,

  /** An evidence input's {@code domainCode} does not match the domain of the hypothesis it is
   * evidence for. */
  CROSS_DOMAIN_EVIDENCE,

  /** The same governed observation id appears more than once in {@code context.evidence()} --
   * whether or not the repeated records agree. Amendment 1 §R is explicit that de-duplicating a
   * benign repeat (the same observation reaching the pipeline through more than one projection,
   * §J vector 10) is {@link HypothesisUncertaintyContextAssembler}'s job, not the calculator's: "the
   * calculator does no de-duplication of its own." A repeated id that still reaches {@link
   * HypothesisUncertaintyCalculatorV1} therefore always means assembly failed to reduce it, and the
   * whole context is refused -- never silently folded together, never one record silently chosen. */
  DUPLICATE_EVIDENCE_OBSERVATION,

  /** A candidate hypothesis is missing one of the identity fields Amendment 1 §H's canonical order
   * requires ({@code relationshipType}, {@code targetObjectiveId}, {@code triggerObjectiveId}, or
   * {@code triggerItemVersionId}; {@code authorizingRelationshipId} may legitimately be {@code null}
   * per {@link io.ramals.learningplatform.assessment.DiagnosticHypothesis}'s own contract). */
  MALFORMED_HYPOTHESIS_IDENTITY
}
