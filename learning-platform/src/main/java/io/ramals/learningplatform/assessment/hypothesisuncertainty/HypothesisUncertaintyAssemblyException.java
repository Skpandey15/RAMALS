package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import java.util.UUID;

/**
 * {@link HypothesisUncertaintyContextAssembler} could not resolve the authoritative curriculum
 * domain for an objective it needed. Distinct from {@link HypothesisUncertaintyValidationException}:
 * that exception is the frozen, versioned contract {@link HypothesisUncertaintyCalculatorV1#calculate}
 * raises on an already-assembled but invalid context (M2-ADR-034 Amendment 1 §Q); this one is an
 * assembly-time failure -- the objective simply does not resolve to any domain -- and carries no
 * Amendment-1 reason code because assembly is not part of the frozen calculator contract.
 */
public class HypothesisUncertaintyAssemblyException extends RuntimeException {

  public HypothesisUncertaintyAssemblyException(UUID objectiveId) {
    super("cannot resolve a curriculum domain for objective " + objectiveId);
  }
}
