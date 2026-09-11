package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import java.util.List;

/**
 * The complete output of {@code HYPOTHESIS_DISCRIMINATION_V1} for one {@link
 * HypothesisDiscriminationContext} (M2-ADR-034 Amendment 2 §G/§L). Immutable.
 *
 * @param engineVersion always the literal {@link
 *     HypothesisDiscriminationCalculatorV1#ENGINE_VERSION}
 * @param status {@link HypothesisDiscriminationStatus#NOT_APPLICABLE} iff {@code
 *     baseResult.status()} was not {@code APPLICABLE}
 * @param probes every scored candidate probe, in Amendment 2 §L canonical emission order (probe's
 *     own hypothesis by Amendment 1 §H canonical order, then {@code probeItemVersionId} ascending)
 *     -- never the input order, never a ranking by score. See {@link
 *     HypothesisDiscriminationCalculatorV1#RANKING_ORDER} for the separate, score-based ranking
 *     view (Amendment 2 §K).
 */
public record HypothesisDiscriminationResult(
    String engineVersion,
    HypothesisDiscriminationStatus status,
    List<CandidateDiscrimination> probes) {

  public HypothesisDiscriminationResult {
    probes = List.copyOf(probes);
  }
}
