package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult;
import java.util.List;

/**
 * The complete input to {@link HypothesisDiscriminationCalculatorV1#calculate} (M2-ADR-034
 * Amendment 2 §F). Immutable.
 *
 * @param baseContext the exact context {@code HYPOTHESIS_UNCERTAINTY_V1} was computed from
 * @param baseResult {@code HYPOTHESIS_UNCERTAINTY_V1}'s own output from that exact context -- must
 *     equal {@code HYPOTHESIS_UNCERTAINTY_V1.calculate(baseContext)} exactly or the context is
 *     refused ({@code BASE_RESULT_MISMATCH}); never recomputed with different semantics
 * @param candidates every candidate probe to score, in any order -- must each target a hypothesis
 *     already in {@code baseContext.candidates()} (Amendment 2 §E)
 */
public record HypothesisDiscriminationContext(
    HypothesisUncertaintyContext baseContext,
    HypothesisUncertaintyResult baseResult,
    List<CandidateProbe> candidates) {

  public HypothesisDiscriminationContext {
    if (baseContext == null) {
      throw new IllegalArgumentException("base context is required");
    }
    if (baseResult == null) {
      throw new IllegalArgumentException("base result is required");
    }
    candidates = List.copyOf(candidates);
  }
}
