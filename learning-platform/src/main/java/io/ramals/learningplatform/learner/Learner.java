package io.ramals.learningplatform.learner;

import java.time.Instant;
import java.util.UUID;

/** A learner identity anchored to an opaque Keycloak subject. Holds no PII. */
public record Learner(
    UUID id,
    String subject,
    String status,
    Instant createdAt,
    Instant updatedAt) {
}
