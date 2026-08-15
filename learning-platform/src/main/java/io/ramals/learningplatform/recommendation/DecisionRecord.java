package io.ramals.learningplatform.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable provenance of one recommendation decision, reconstructable from its own columns. */
public record DecisionRecord(
    UUID id,
    UUID learnerId,
    UUID skillId,
    UUID curriculumVersionId,
    String decisionType,
    RecommendedAction recommendedAction,
    String reasonCode,
    String masteryStatus,
    UUID sourceSnapshotId,
    int aggregateVersion,
    BigDecimal masteryScore,
    BigDecimal evidenceConfidence,
    BigDecimal masteryThreshold,
    BigDecimal confidenceThreshold,
    int evidenceCount,
    int itemsConsidered,
    String masteryAlgorithmVersion,
    String confidenceAlgorithmVersion,
    String policyVersion,
    String interactionId,
    String traceId,
    Instant decidedAt) {
}
