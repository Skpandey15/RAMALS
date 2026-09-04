package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): a deterministic, auditable hypothesis raised by one unexpected miss
 * -- never a diagnosis. Nothing named or shaped like {@code confirmedRootCause} exists anywhere in
 * this model; a hypothesis stays a hypothesis until something outside this class (not built here --
 * see the ADR's §4) decides otherwise.
 *
 * @param triggerItemVersionId the item whose miss raised this hypothesis
 * @param triggerObjectiveId the objective the trigger item is tagged to
 * @param relationshipType which of the four {@link ProbeRelationshipType} semantics raised it
 * @param targetObjectiveId the related objective this hypothesis is about
 * @param authorizingRelationshipId the {@code core.diagnostic_probe_relationship} row that
 *     authorized this hypothesis, for {@link ProbeRelationshipType#ROOT_CAUSE_PROBE} and
 *     {@link ProbeRelationshipType#CONTRADICTION_CHECK}. {@code null} for
 *     {@link ProbeRelationshipType#SAME_OBJECTIVE_CONFIRMATION} and
 *     {@link ProbeRelationshipType#PREREQUISITE_VALIDATION}, which are authorized by
 *     {@code core.assessment_item_objective} and {@code core.skill_prerequisite} directly rather
 *     than by a row in the new table -- see M2-ADR-024 §1.
 */
public record DiagnosticHypothesis(
    UUID triggerItemVersionId,
    UUID triggerObjectiveId,
    ProbeRelationshipType relationshipType,
    UUID targetObjectiveId,
    UUID authorizingRelationshipId) {
}
