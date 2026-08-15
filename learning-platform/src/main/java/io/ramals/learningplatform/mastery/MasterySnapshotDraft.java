package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.util.UUID;

/** All fields required to append one mastery snapshot for a given aggregate version. */
public record MasterySnapshotDraft(
    UUID learnerId,
    UUID skillId,
    UUID curriculumVersionId,
    int aggregateVersion,
    BigDecimal masteryScore,
    MasteryStatus status,
    BigDecimal threshold,
    BigDecimal evidenceConfidence,
    BigDecimal confidenceThreshold,
    int evidenceCount,
    int itemsConsidered,
    String algorithmVersion,
    String confidenceAlgorithmVersion,
    String interactionId) {
}
