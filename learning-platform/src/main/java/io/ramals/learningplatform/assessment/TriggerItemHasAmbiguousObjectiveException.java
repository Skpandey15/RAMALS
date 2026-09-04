package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.UUID;

/**
 * Raised when {@link ProbeRelationshipService#resolve} is asked to reason from an item tagged to
 * more than one {@code core.assessment_item_objective} row. No content this platform has ever
 * authored does this -- every seeded item is tagged to exactly one objective -- but
 * {@link DiagnosticHypothesis} has a single {@code triggerObjectiveId} field, so there is no
 * non-arbitrary way to pick one of several without inventing a tie-break this foundation has no
 * authority to make (M2-ADR-024's amendment, the same reasoning that makes multiple target
 * objectives {@link ProbeResolutionOutcome#AMBIGUOUS_TARGET_OBJECTIVE} rather than an arbitrary
 * choice). This fails closed with the full set of objectives found, rather than resolving against
 * whichever one a query happened to return first.
 */
public class TriggerItemHasAmbiguousObjectiveException extends RuntimeException {

  private final List<UUID> objectiveIds;

  public TriggerItemHasAmbiguousObjectiveException(UUID itemVersionId, List<UUID> objectiveIds) {
    super("Item " + itemVersionId + " is tagged to more than one objective (" + objectiveIds
        + "); a probe relationship cannot be resolved from an ambiguous trigger objective.");
    this.objectiveIds = List.copyOf(objectiveIds);
  }

  public List<UUID> objectiveIds() {
    return objectiveIds;
  }
}
