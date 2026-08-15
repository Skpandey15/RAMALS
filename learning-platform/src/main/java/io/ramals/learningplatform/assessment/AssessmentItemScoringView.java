package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.UUID;

/**
 * Server-only scoring projection of an item, including the answer key. Never serialized to a
 * learner; used solely inside the submission transaction to decide correctness.
 */
public record AssessmentItemScoringView(
    UUID itemVersionId,
    String skillCode,
    String itemType,
    List<String> optionIds,
    List<String> correctOptions) {
}
