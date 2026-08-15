package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.util.List;

/** Explicit inputs to the evidence confidence calculation, assembled from a skill's evidence. */
public record ConfidenceInputs(
    int uniqueScoredItems,
    int requiredEvidenceCount,
    int coveredRequiredObjectives,
    int requiredObjectives,
    long latestEvidenceAgeDays,
    List<BigDecimal> normalizedScores) {
}
