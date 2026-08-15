package io.ramals.learningplatform.assessment;

/** Result of an idempotent attempt-creation call and whether a new attempt was persisted. */
public record AttemptCreation(
    AssessmentAttempt attempt,
    ResolvedDiagnostic diagnostic,
    boolean created) {
}
