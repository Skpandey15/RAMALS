package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * A candidate for an adaptive diagnostic form: one verified, scoreable item of the pinned
 * assessment version, together with its logical question identity and skill.
 *
 * <p>Unlike {@link EligibleItem}, this carries no recency signal at all. V1's recency is a
 * preference a thin pool may override; {@link AdaptiveDiagnosticSelector}'s no-repeat rule is a
 * hard exclusion applied by the caller before the pool ever reaches the selector -- see
 * {@link AssessmentRepository#findLearnerExposedLogicalItemIds}. An item that has already been
 * shown to this learner, under any version sharing its {@code logicalItemId}, is never a candidate
 * at all, so there is nothing here for a preference to weigh.
 */
public record AdaptiveEligibleItem(
    UUID itemVersionId,
    UUID logicalItemId,
    UUID skillId,
    String skillCode,
    String itemType,
    String difficulty) {
}
