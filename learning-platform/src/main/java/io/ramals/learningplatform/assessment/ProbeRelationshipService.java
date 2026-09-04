package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentItemType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * H4b foundation (M2-ADR-024): wires {@link ProbeRelationshipRepository}'s reads into
 * {@link ProbeRelationshipResolver}'s pure decision, and separately turns a probe's own response
 * into {@link HypothesisEvidence}. Read-only, by construction -- no writer dependency at all, the
 * same discipline {@code GapDiagnosisService} already holds for H1's read-only module boundary
 * (M2-ADR-023), applied here one layer over in {@code assessment} rather than {@code diagnosis} --
 * see M2-ADR-024 §2 for why.
 *
 * <p><b>Called by nothing at runtime yet.</b> This foundation is deliberately not wired into
 * {@code DiagnosticService} or {@code DiagnosticSubmissionService} -- see M2-ADR-024 §4. It exists to
 * be reviewed, and to be tested end to end against real content, on its own.
 */
@Service
public class ProbeRelationshipService {

  private final ProbeRelationshipRepository repository;
  private final AssessmentRepository assessmentRepository;

  public ProbeRelationshipService(
      ProbeRelationshipRepository repository, AssessmentRepository assessmentRepository) {
    this.repository = repository;
    this.assessmentRepository = assessmentRepository;
  }

  /**
   * Resolves a probe for one trigger item's miss under one relationship type, for one learner.
   *
   * @throws TriggerItemHasNoObjectiveException if the trigger item carries no
   *     {@code assessment_item_objective} tag at all -- a data-quality state
   *     {@code core.assessment_item_objective}'s own tagging discipline should make unreachable for
   *     any item that ever reaches a learner, but this fails closed rather than silently resolving
   *     against a null objective.
   */
  @Transactional(readOnly = true)
  public ProbeResolution resolve(
      UUID triggerItemVersionId, ProbeRelationshipType relationshipType, UUID learnerId) {
    UUID assessmentVersionId = repository.assessmentVersionIdForItem(triggerItemVersionId)
        .orElseThrow(() -> new TriggerItemHasNoObjectiveException(triggerItemVersionId));
    UUID triggerObjectiveId = repository.objectiveIdForItem(triggerItemVersionId)
        .orElseThrow(() -> new TriggerItemHasNoObjectiveException(triggerItemVersionId));

    ProbeTargetObjective target = resolveTarget(triggerObjectiveId, relationshipType);
    if (target == null) {
      return ProbeRelationshipResolver.resolve(
          triggerItemVersionId, triggerObjectiveId, relationshipType, null, List.of(), Set.of());
    }

    List<ProbeCandidateItem> allItemsForTarget = repository.itemsForObjective(
        assessmentVersionId, target.objectiveId(), triggerItemVersionId);
    Set<UUID> exposed = assessmentRepository.findLearnerExposedLogicalItemIds(learnerId);

    return ProbeRelationshipResolver.resolve(
        triggerItemVersionId, triggerObjectiveId, relationshipType, target, allItemsForTarget, exposed);
  }

  private ProbeTargetObjective resolveTarget(UUID triggerObjectiveId, ProbeRelationshipType type) {
    return switch (type) {
      case SAME_OBJECTIVE_CONFIRMATION -> repository.sameObjectiveTarget(triggerObjectiveId);
      case PREREQUISITE_VALIDATION ->
          repository.prerequisiteValidationTarget(triggerObjectiveId).orElse(null);
      case ROOT_CAUSE_PROBE, CONTRADICTION_CHECK ->
          repository.authoredRelationshipTarget(triggerObjectiveId, type).orElse(null);
    };
  }

  /**
   * Reads {@code probeItemVersionId}'s response within {@code attemptId} and classifies it as
   * evidence for {@code hypothesis} -- pure interpretation of an already-written
   * {@code core.assessment_response} row, writing nothing. {@code probeItemVersionId} is always one
   * of {@code hypothesis}'s own resolved candidates, chosen by whatever selected and presented it;
   * this method does not choose it. Empty if that item was never answered in that attempt.
   */
  @Transactional(readOnly = true)
  public Optional<HypothesisEvidence> evidenceFor(
      DiagnosticHypothesis hypothesis, UUID attemptId, UUID probeItemVersionId) {
    return repository.scoredProbeResponse(attemptId, probeItemVersionId)
        .map(response -> new HypothesisEvidence(
            hypothesis, probeItemVersionId, response.isCorrect(),
            HypothesisEvidenceOutcome.classify(
                AssessmentItemType.of(response.itemType()), response.isCorrect())));
  }
}
