package io.ramals.learningplatform.learning;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * Drives a session transition. {@code expectedVersion} is the version the client last saw; the
 * transition applies only if it still matches, giving optimistic concurrency control. An optional
 * checkpoint payload (a JSON object) is persisted with the transition.
 */
public record SessionTransitionRequest(
    @NotNull LearningSessionCommand command,
    @NotNull @Min(1) Integer expectedVersion,
    JsonNode checkpoint) {
}
