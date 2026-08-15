package io.ramals.learningplatform.admin;

import java.time.Instant;
import java.util.UUID;

/** An immutable record of a privileged content operation or a rejected attempt. */
public record AdminActivity(
    UUID id,
    String actorSubject,
    String action,
    String targetType,
    UUID targetId,
    String outcome,
    String detail,
    String interactionId,
    String traceId,
    Instant createdAt) {
}
