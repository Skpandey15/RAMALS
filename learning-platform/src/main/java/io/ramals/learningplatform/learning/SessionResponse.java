package io.ramals.learningplatform.learning;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Learner-facing view of a session. Exposes the version so clients can drive optimistic transitions. */
public record SessionResponse(
    String sessionId,
    String domainCode,
    String versionCode,
    String status,
    int version,
    JsonNode checkpoint,
    Instant startedAt,
    Instant updatedAt,
    Instant completedAt) {

  static SessionResponse from(LearningSession session) {
    return new SessionResponse(
        session.id().toString(),
        session.domainCode(),
        session.versionCode(),
        session.status().name(),
        session.version(),
        session.checkpoint(),
        session.startedAt(),
        session.updatedAt(),
        session.completedAt());
  }
}
