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

  /** The same governed observation id appears more than once with disagreeing outcomes or against
   * disagreeing hypotheses -- see {@link HypothesisUncertaintyCalculatorV1}'s javadoc for how this
   * reconciles Amendment 1 §J vector 10 (a benign repeat -- same id, same hypothesis, same outcome --
   * is silently de-duplicated) with §Q (a repeat that disagrees is corrupt input and is rejected). */
  DUPLICATE_EVIDENCE_OBSERVATION,

  /** A candidate hypothesis is missing one of the identity fields Amendment 1 §H's canonical order
   * requires ({@code relationshipType}, {@code targetObjectiveId}, {@code triggerObjectiveId}, or
   * {@code triggerItemVersionId}; {@code authorizingRelationshipId} may legitimately be {@code null}
   * per {@link io.ramals.learningplatform.assessment.DiagnosticHypothesis}'s own contract). */
  MALFORMED_HYPOTHESIS_IDENTITY
}
