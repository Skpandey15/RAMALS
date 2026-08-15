package io.ramals.learningplatform.recommendation;

import java.time.Instant;
import java.util.UUID;

/** The current recommendation surface for a learner and skill, pointing at its decision record. */
public record LearningRecommendation(
    UUID id,
    UUID learnerId,
    UUID skillId,
    String skillCode,
    UUID curriculumVersionId,
    RecommendedAction recommendedAction,
    String reasonCode,
    String masteryStatus,
    UUID decisionRecordId,
    UUID sourceSnapshotId,
    Instant createdAt) {
}
