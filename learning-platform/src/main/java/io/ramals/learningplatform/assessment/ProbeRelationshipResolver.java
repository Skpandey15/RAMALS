package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): the pure decision behind turning a trigger item's miss into a
 * servable probe -- given a resolved candidate target objective and its items, decide the
 * {@link ProbeResolutionOutcome} and, where one exists, the ordered, unseen candidate list. Kept
 * separate from any repository so it can be tested from a fixed vector with no database, the same
 * split {@link PrerequisiteAwareDiagnosticSelector} and {@link HypothesisConfirmationDiagnosticSelector}
 * already keep from {@code DiagnosticService}.
 *
 * <p><b>Resolving which target objective to consider is not this class's job.</b> That is a
 * repository/service concern -- reading {@code core.assessment_item_objective},
 * {@code core.skill_prerequisite}, or {@code core.diagnostic_probe_relationship} depending on the
 * {@link ProbeRelationshipType} in play (M2-ADR-024 §1) -- resolved once into a single
 * {@link ProbeTargetObjective} (or none) before this class ever runs. This class only ever decides,
 * for that one already-chosen objective: does it have any items at all, and of those, does this
 * learner have any left unseen.
 *
 * <p><b>Read-only, and produces nothing authoritative.</b> Reads nothing and writes nothing; a call
 * with the same inputs always produces the same {@link ProbeResolution}. Never mutates mastery,
 * never confirms a diagnosis -- see {@link DiagnosticHypothesis}.
 */
public final class ProbeRelationshipResolver {

  private ProbeRelationshipResolver() {
  }

  /**
   * @param triggerItemVersionId the item whose miss is being investigated
   * @param triggerObjectiveId the objective the trigger item is tagged to
   * @param relationshipType which semantics this resolution is under
   * @param target the single, already-resolved candidate target objective, or {@code null} if none
   *     exists at all for this trigger under this relationship type
   * @param allItemsForTarget every verified, scoreable item tagged to {@code target}'s objective,
   *     deterministically ordered (item {@code display_order}, then {@code item_code}) by the
   *     caller -- ignored if {@code target} is {@code null}
   * @param exposedLogicalItemIds this learner's full exposure set, keyed by logical question
   *     identity -- the same set {@code AssessmentRepository.findLearnerExposedLogicalItemIds}
   *     already resolves for every other selector's no-repeat exclusion
   */
  public static ProbeResolution resolve(
      UUID triggerItemVersionId,
      UUID triggerObjectiveId,
      ProbeRelationshipType relationshipType,
      ProbeTargetObjective target,
      List<ProbeCandidateItem> allItemsForTarget,
      Set<UUID> exposedLogicalItemIds) {
    if (target == null) {
      return new ProbeResolution(ProbeResolutionOutcome.NO_RELATIONSHIP_DEFINED, null, List.of());
    }

    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        triggerItemVersionId, triggerObjectiveId, relationshipType, target.objectiveId(),
        target.authorizingRelationshipId());

    if (allItemsForTarget.isEmpty()) {
      return new ProbeResolution(
          ProbeResolutionOutcome.RELATIONSHIP_DEFINED_BUT_NO_ITEMS, hypothesis, List.of());
    }

    List<ProbeCandidateItem> unseen = allItemsForTarget.stream()
        .filter(item -> !exposedLogicalItemIds.contains(item.logicalItemId()))
        .toList();

    if (unseen.isEmpty()) {
      return new ProbeResolution(
          ProbeResolutionOutcome.ALL_CANDIDATES_ALREADY_EXPOSED, hypothesis, List.of());
    }

    return new ProbeResolution(ProbeResolutionOutcome.CANDIDATES_AVAILABLE, hypothesis, unseen);
  }
}
