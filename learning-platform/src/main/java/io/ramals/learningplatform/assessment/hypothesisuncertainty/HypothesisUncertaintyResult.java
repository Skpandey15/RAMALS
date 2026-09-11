package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import java.util.List;

/**
 * The complete output of {@code HYPOTHESIS_UNCERTAINTY_V1} for one {@link
 * HypothesisUncertaintyContext} (M2-ADR-034 Amendment 1 §K). Immutable.
 *
 * @param engineVersion always the literal {@link HypothesisUncertaintyCalculatorV1#ENGINE_VERSION}
 * @param status {@link HypothesisUncertaintyStatus#NOT_APPLICABLE} iff {@code candidates} is empty
 * @param candidates every candidate from the request, in Amendment 1 §H canonical order -- never
 *     reordered by database or collection iteration order
 */
public record HypothesisUncertaintyResult(
    String engineVersion,
    HypothesisUncertaintyStatus status,
    List<CandidateUncertainty> candidates) {

  public HypothesisUncertaintyResult {
    candidates = List.copyOf(candidates);
  }
}
