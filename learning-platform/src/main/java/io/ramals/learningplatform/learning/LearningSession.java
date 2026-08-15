package io.ramals.learningplatform.learning;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A durable, resumable learner journey over a curriculum version. */
public record LearningSession(
    UUID id,
    UUID learnerId,
    UUID curriculumVersionId,
    String domainCode,
    String versionCode,
    LearningSessionStatus status,
    int version,
    JsonNode checkpoint,
    Instant startedAt,
    Instant updatedAt,
    Instant completedAt) {
}
