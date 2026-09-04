package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): the pure decision behind turning a trigger item's miss into a
 * servable probe -- given every candidate target objective and, when there is exactly one, its
 * items, decide the {@link ProbeResolutionOutcome} and, where one exists, the ordered, unseen
 * candidate list. Kept separate from any repository so it can be tested from a fixed vector with no
 * database, the same split {@link PrerequisiteAwareDiagnosticSelector} and
 * {@link HypothesisConfirmationDiagnosticSelector} already keep from {@code DiagnosticService}.
 *
 * <p><b>Resolving candidate target objectives is not this class's job.</b> That is a
 * repository/service concern -- reading {@code core.assessment_item_objective},
 * {@code core.skill_prerequisite}, or {@code core.diagnostic_probe_relationship} depending on the
 * {@link ProbeRelationshipType} in play (M2-ADR-024 §1) -- resolved into a list of
 * {@link ProbeTargetObjective} candidates before this class ever runs.
 *
 * <p><b>More than one candidate is reported, not ranked or arbitrated.</b> The schema permits more
 * than one published {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK} relationship from the same
 * source objective (uniqueness is on {@code (source, target, type)}, not {@code (source, type)}),
 * and a trigger skill may have more than one curriculum prerequisite or a prerequisite may have more
 * than one required objective. Picking one of several by a fixed tie-break -- lowest id, first
 * {@code display_order} -- would not be a deterministic reading of a diagnostic fact, it would be an
 * uncredited diagnostic *policy* this foundation has no authority to make (M2-ADR-024's amendment).
 * {@link ProbeResolutionOutcome#AMBIGUOUS_TARGET_OBJECTIVE} exists so that ambiguity is itself an
 * auditable, explicit result instead. Considering several candidates together to produce a ranked or
 * combined probe is a real future capability -- deliberately not built here.
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
   * @param triggerObjectiveId the objective the trigger item is tagged to -- already established as
   *     the trigger's single objective before this call; see
   *     {@link TriggerItemHasAmbiguousObjectiveException} for why a trigger tagged to more than one
   *     objective never reaches this method at all
   * @param relationshipType which semantics this resolution is under
   * @param targetCandidates every candidate target objective the repository resolved for this
   *     trigger under this relationship type, in its own deterministic order. Empty yields
   *     {@code NO_RELATIONSHIP_DEFINED}; more than one yields {@code AMBIGUOUS_TARGET_OBJECTIVE}
   *     without inspecting {@code allItemsForTheOnlyTarget} at all; exactly one proceeds to the
   *     items/exposure decision below
   * @param allItemsForTheOnlyTarget every verified, scoreable item tagged to the single candidate's
   *     objective, deterministically ordered (item {@code display_order}, then {@code item_code}) by
   *     the caller -- meaningless, and ignored, unless {@code targetCandidates} has exactly one
   *     element
   * @param exposedLogicalItemIds this learner's full exposure set, keyed by logical question
   *     identity -- the same set {@code AssessmentRepository.findLearnerExposedLogicalItemIds}
   *     already resolves for every other selector's no-repeat exclusion
   */
  public static ProbeResolution resolve(
      UUID triggerItemVersionId,
      UUID triggerObjectiveId,
      ProbeRelationshipType relationshipType,
      List<ProbeTargetObjective> targetCandidates,
      List<ProbeCandidateItem> allItemsForTheOnlyTarget,
      Set<UUID> exposedLogicalItemIds) {
    if (targetCandidates.isEmpty()) {
      return new ProbeResolution(
          ProbeResolutionOutcome.NO_RELATIONSHIP_DEFINED, null, List.of(), List.of());
    }
    if (targetCandidates.size() > 1) {
      List<UUID> ambiguousObjectiveIds = targetCandidates.stream()
          .map(ProbeTargetObjective::objectiveId)
          .toList();
      return new ProbeResolution(
          ProbeResolutionOutcome.AMBIGUOUS_TARGET_OBJECTIVE, null, List.of(), ambiguousObjectiveIds);
    }

    ProbeTargetObjective target = targetCandidates.get(0);
    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        triggerItemVersionId, triggerObjectiveId, relationshipType, target.objectiveId(),
        target.authorizingRelationshipId());

    if (allItemsForTheOnlyTarget.isEmpty()) {
      return new ProbeResolution(
          ProbeResolutionOutcome.RELATIONSHIP_DEFINED_BUT_NO_ITEMS, hypothesis, List.of(), List.of());
    }

    List<ProbeCandidateItem> unseen = allItemsForTheOnlyTarget.stream()
        .filter(item -> !exposedLogicalItemIds.contains(item.logicalItemId()))
        .toList();

    if (unseen.isEmpty()) {
      return new ProbeResolution(
          ProbeResolutionOutcome.ALL_CANDIDATES_ALREADY_EXPOSED, hypothesis, List.of(), List.of());
    }

    return new ProbeResolution(ProbeResolutionOutcome.CANDIDATES_AVAILABLE, hypothesis, unseen, List.of());
  }
}
