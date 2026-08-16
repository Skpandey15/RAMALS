package io.ramals.learningplatform.security;

import java.time.Instant;
import java.util.UUID;

/** One immutable authentication/authorization decision recorded in {@code audit.security_audit}. */
public record SecurityAuditEntry(
    UUID id,
    String eventType,
    String outcome,
    String subject,
    String httpMethod,
    String route,
    Integer statusCode,
    String reasonCode,
    String detail,
    String interactionId,
    String traceId,
    Instant createdAt) {
}
