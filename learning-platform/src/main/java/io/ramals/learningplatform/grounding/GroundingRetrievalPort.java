package io.ramals.learningplatform.grounding;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage boundary for deterministic, authorized grounding selection. */
public interface GroundingRetrievalPort {
  Optional<AuthorizedGroundingFacts> retrieve(
      String authenticatedSubject,
      UUID curriculumVersionId,
      Instant asOf,
      GroundingRetrievalPolicy policy);

  void appendRetrievalRecord(GroundedContext context, UUID learnerId);
}
