package io.ramals.learningplatform.grounding;

import java.util.Set;

/** One bounded proposal claim and the exact supplied-context facts asserted to support it. */
public record GroundedClaim(String claimId, Set<String> evidenceIds) {
  public GroundedClaim {
    evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds);
  }
}
