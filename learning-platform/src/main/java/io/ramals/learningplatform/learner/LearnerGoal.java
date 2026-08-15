package io.ramals.learningplatform.learner;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A learner's single active learning goal, resolved to its domain code. */
public record LearnerGoal(
    UUID learnerId,
    String targetDomainCode,
    BigDecimal targetProficiency,
    LocalDate targetDate,
    Instant createdAt,
    Instant updatedAt) {
}
