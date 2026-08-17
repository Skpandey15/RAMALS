package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/**
 * What the learner is working towards, when that is known and relevant.
 *
 * <p>Optional throughout. The authoritative learner model carries one goal today, and richer goal
 * structures are a later concern; the shape exists now so agents are written against a general goal
 * rather than against a single target domain.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LearningGoalContext(
    GoalType goalType,
    String goalCode,
    LocalDate targetDate,
    String goalVersion) {
}
