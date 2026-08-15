package io.ramals.learningplatform.learner;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Public view of a learner's own goal. */
public record LearnerGoalResponse(
    String targetDomainCode,
    BigDecimal targetProficiency,
    LocalDate targetDate,
    Instant createdAt,
    Instant updatedAt) {

  static LearnerGoalResponse from(LearnerGoal goal) {
    return new LearnerGoalResponse(
        goal.targetDomainCode(),
        goal.targetProficiency(),
        goal.targetDate(),
        goal.createdAt(),
        goal.updatedAt());
  }
}
