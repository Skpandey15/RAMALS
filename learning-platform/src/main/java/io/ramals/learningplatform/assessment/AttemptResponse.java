package io.ramals.learningplatform.assessment;

import java.time.Instant;

/** Attempt-creation response. Contains no answer key or scoring internals. */
public record AttemptResponse(
    String attemptId,
    String status,
    String domainCode,
    String assessmentCode,
    String assessmentVersionCode,
    String idempotencyKey,
    Instant createdAt) {

  static AttemptResponse from(AttemptCreation creation) {
    AssessmentAttempt attempt = creation.attempt();
    ResolvedDiagnostic diagnostic = creation.diagnostic();
    return new AttemptResponse(
        attempt.id().toString(),
        attempt.status(),
        diagnostic.domainCode(),
        diagnostic.assessmentCode(),
        diagnostic.versionCode(),
        attempt.idempotencyKey(),
        attempt.createdAt());
  }
}
