package io.ramals.learningplatform.grounding;

import java.util.List;
import java.util.Set;

/** Deterministic gate outcome and normalized evidence set ready for immutable persistence. */
public record ProposalGateResult(
    boolean accepted,
    List<ProposalGateReason> reasons,
    Set<String> referencedEvidenceIds) {

  public ProposalGateResult {
    reasons = List.copyOf(reasons);
    referencedEvidenceIds = Set.copyOf(referencedEvidenceIds);
  }
}
