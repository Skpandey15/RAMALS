package io.ramals.learningplatform.learner;

import java.time.Instant;

/** Public view of a learner's own context. Exposes no subject or PII. */
public record LearnerProfileResponse(
    String learnerId,
    String status,
    Instant createdAt) {

  static LearnerProfileResponse from(Learner learner) {
    return new LearnerProfileResponse(
        learner.id().toString(), learner.status(), learner.createdAt());
  }
}
