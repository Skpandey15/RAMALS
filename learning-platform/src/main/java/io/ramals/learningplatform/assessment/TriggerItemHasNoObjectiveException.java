package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * Raised when {@link ProbeRelationshipService#resolve} is asked to reason from an item that either
 * does not exist or carries no {@code core.assessment_item_objective} tag.
 * {@code core.assessment_item_objective}'s own tagging discipline (V046) should make the latter
 * unreachable for any item ever presented to a learner, but a probe relationship has nothing to
 * resolve without a trigger objective, so this fails closed rather than resolving against a null
 * one.
 */
public class TriggerItemHasNoObjectiveException extends RuntimeException {

  public TriggerItemHasNoObjectiveException(UUID itemVersionId) {
    super("Item " + itemVersionId + " does not exist or has no assessment_item_objective tag; a "
        + "probe relationship cannot be resolved without a trigger objective.");
  }
}
