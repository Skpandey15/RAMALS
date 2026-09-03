package io.ramals.learningplatform.evidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An immutable observation of a learner's performance on a skill. */
public record Evidence(
    UUID id,
    UUID learnerId,
    UUID skillId,
    String evidenceType,
    String sourceType,
    UUID sourceAttemptId,
    UUID sourceAssessmentVersionId,
    String scoringVersion,
    UUID adjustsEvidenceId,
    String lineageKey,
    BigDecimal observedScore,
    BigDecimal normalizedScore,
    int itemsAnswered,
    int itemsCorrect,
    EvidenceCoverage coverage,
    String interactionId,
    Instant occurredAt,
    Instant recordedAt) {
}
