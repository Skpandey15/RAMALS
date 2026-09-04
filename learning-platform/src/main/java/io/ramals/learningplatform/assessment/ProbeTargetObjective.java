package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): one candidate target objective a relationship resolution found for
 * one trigger -- assembled by the repository/service layer from whichever source is authoritative
 * for the relationship type in play ({@code core.assessment_item_objective},
 * {@code core.skill_prerequisite}, or {@code core.diagnostic_probe_relationship}), and passed to
 * {@link ProbeRelationshipResolver} as a list of every candidate found, not just one.
 *
 * <p><b>The repository never picks a "best" one when more than one exists.</b> A skill with two
 * curriculum prerequisites, a prerequisite with two required objectives, or two published
 * {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK} rows from the same source objective (the
 * schema's own uniqueness constraint, {@code (source_objective_id, target_objective_id,
 * relationship_type)}, permits exactly this) all resolve to more than one
 * {@code ProbeTargetObjective} in the list {@link ProbeRelationshipResolver} receives, and it
 * reports {@link ProbeResolutionOutcome#AMBIGUOUS_TARGET_OBJECTIVE} rather than choosing one by a
 * tie-break that would be an uncredited diagnostic policy decision, not a deterministic reading of a
 * fact. Considering multiple target objectives together to produce a ranked or combined probe is a
 * real future capability, not decided here.
 *
 * @param objectiveId a related objective this hypothesis might be about
 * @param authorizingRelationshipId see {@link DiagnosticHypothesis#authorizingRelationshipId()};
 *     {@code null} for the two relationship types with no row of their own
 */
public record ProbeTargetObjective(UUID objectiveId, UUID authorizingRelationshipId) {
}
