package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.UUID;

/** A learner's attempt at a specific, version-pinned assessment. */
public record AssessmentAttempt(
    UUID id,
    UUID learnerId,
    UUID assessmentVersionId,
    String status,
    String idempotencyKey,
    Instant createdAt,
    Instant updatedAt) {
}
