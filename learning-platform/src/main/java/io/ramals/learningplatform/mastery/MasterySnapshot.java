package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An immutable, versioned record of a learner's computed mastery of a skill. */
public record MasterySnapshot(
    UUID id,
    UUID learnerId,
    UUID skillId,
    UUID curriculumVersionId,
    int aggregateVersion,
    BigDecimal masteryScore,
    MasteryStatus status,
    BigDecimal threshold,
    int evidenceCount,
    int itemsConsidered,
    String algorithmVersion,
    String interactionId,
    Instant calculatedAt) {
}
