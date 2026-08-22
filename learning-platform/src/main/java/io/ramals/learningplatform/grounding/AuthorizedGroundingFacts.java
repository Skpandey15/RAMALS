package io.ramals.learningplatform.grounding;

import java.util.List;
import java.util.UUID;

/** Learner-scoped facts returned by the repository after authorization has been applied in SQL. */
public record AuthorizedGroundingFacts(UUID learnerId, List<GroundedContextItem> items) {
  public AuthorizedGroundingFacts {
    items = List.copyOf(items);
  }
}
