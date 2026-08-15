package io.ramals.learningplatform.assessment;

import java.util.List;

/** Outcome of a diagnostic submission: the finalized attempt and its per-skill scores. */
public record SubmissionResult(
    AssessmentAttempt attempt,
    int itemsAnswered,
    List<SkillScore> skillScores) {
}
