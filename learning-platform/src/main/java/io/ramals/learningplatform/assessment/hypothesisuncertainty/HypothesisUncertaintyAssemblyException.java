package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import java.util.UUID;

/**
 * {@link HypothesisUncertaintyContextAssembler} could not assemble a valid {@link
 * HypothesisUncertaintyContext}. Distinct from {@link HypothesisUncertaintyValidationException}:
 * that exception is the frozen, versioned contract {@link HypothesisUncertaintyCalculatorV1#calculate}
 * raises on an already-assembled but invalid context (M2-ADR-034 Amendment 1 §Q); this one is an
 * assembly-time failure -- the context was never built -- and carries no Amendment-1 reason code
 * because assembly is not part of the frozen calculator contract.
 */
public class HypothesisUncertaintyAssemblyException extends RuntimeException {

  private HypothesisUncertaintyAssemblyException(String message) {
    super(message);
  }

  /** An objective does not resolve to any curriculum domain. */
  public static HypothesisUncertaintyAssemblyException unresolvableDomain(UUID objectiveId) {
    return new HypothesisUncertaintyAssemblyException(
        "cannot resolve a curriculum domain for objective " + objectiveId);
  }

  /**
   * The same governed observation id was returned by more than one read with a disagreeing
   * hypothesis or outcome (Amendment 1 §R) -- a data-integrity failure in the underlying reads, not
   * a benign repeat. The assembler never silently picks one of the conflicting records.
   */
  public static HypothesisUncertaintyAssemblyException conflictingObservation(UUID observationId) {
    return new HypothesisUncertaintyAssemblyException(
        "governed observation " + observationId
            + " was returned with disagreeing records by more than one read");
  }
}
