package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): one verified, scoreable item tagged to a probe's target objective,
 * carrying its logical question identity so the caller can apply the same no-repeat exclusion every
 * other selector already honours -- see {@link ProbeRelationshipResolver}.
 */
public record ProbeCandidateItem(UUID itemVersionId, UUID logicalItemId) {
}
