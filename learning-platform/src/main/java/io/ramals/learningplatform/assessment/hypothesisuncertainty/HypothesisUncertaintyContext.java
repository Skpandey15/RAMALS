package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import java.util.List;
import java.util.UUID;

/**
 * The complete, already-assembled input to {@link HypothesisUncertaintyCalculatorV1#calculate}
 * (M2-ADR-034 Amendment 1 §Q). Immutable. Scoped to exactly **one learner, one diagnostic
 * interaction, one curriculum domain** -- the calculator re-validates that boundary; it is never
 * assumed.
 *
 * <p>Assembly (turning repository state into this context) is a separate concern from calculation
 * -- see {@link HypothesisUncertaintyContextAssembler}. This type itself touches no database, no
 * clock, and no random source.
 *
 * @param interactionId the one diagnostic interaction (attempt) every candidate and every evidence
 *     input is scoped to (Amendment 1 §F)
 * @param domainCode the one curriculum domain every candidate must resolve to
 * @param candidates the already-authorized bounded candidate set (Amendment 1 §E) -- this type
 *     creates none of its own
 * @param evidence every governed evidence observation offered for those candidates, in any order --
 *     evidence for a candidate this list omits is simply absent (band {@code INSUFFICIENT_EVIDENCE})
 */
public record HypothesisUncertaintyContext(
    UUID interactionId,
    String domainCode,
    List<CandidateHypothesis> candidates,
    List<HypothesisEvidenceInput> evidence) {

  public HypothesisUncertaintyContext {
    if (interactionId == null) {
      throw new IllegalArgumentException("context interaction id is required");
    }
    if (domainCode == null || domainCode.isBlank()) {
      throw new IllegalArgumentException("context domain code is required");
    }
    candidates = List.copyOf(candidates);
    evidence = List.copyOf(evidence);
  }
}
