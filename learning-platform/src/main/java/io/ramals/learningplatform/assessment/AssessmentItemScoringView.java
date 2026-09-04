package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.UUID;

/**
 * Server-only scoring projection of an item, including the answer key. Never serialized to a
 * learner; used solely inside the submission transaction to decide correctness.
 *
 * <p>{@code correctOptions} is populated for SINGLE_CHOICE and empty otherwise; {@code
 * acceptedAnswers} is populated for FILL_BLANK and empty otherwise. A view for any other item type
 * should never reach this record -- the repository queries that build it are scoped to
 * deterministically scoreable types.
 */
public record AssessmentItemScoringView(
    UUID itemVersionId,
    String skillCode,
    String itemType,
    List<String> optionIds,
    List<String> correctOptions,
    List<String> acceptedAnswers) {
}
