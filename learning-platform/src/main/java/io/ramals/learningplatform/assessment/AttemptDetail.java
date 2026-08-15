package io.ramals.learningplatform.assessment;

import java.util.List;

/** An attempt together with its resolved diagnostic and answer-key-free items. */
public record AttemptDetail(
    AssessmentAttempt attempt,
    ResolvedDiagnostic diagnostic,
    List<DiagnosticItem> items) {
}
