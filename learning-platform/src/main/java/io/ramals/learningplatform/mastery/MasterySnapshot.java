package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** An immutable, versioned record of a learner's computed mastery and evidence confidence. */
public record MasterySnapshot(
    UUID id,
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
    String statusPolicyVersion,
    BigDecimal objectiveCoverage,
    Set<MasteryDifficultyBand> coveredDifficultyBands,
    String interactionId,
    Instant calculatedAt) {
}
