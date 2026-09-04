package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): the single, deterministically-chosen candidate target objective a
 * relationship resolution found for one trigger -- input to {@link ProbeRelationshipResolver},
 * assembled by the repository/service layer from whichever source is authoritative for the
 * relationship type in play ({@code core.assessment_item_objective},
 * {@code core.skill_prerequisite}, or {@code core.diagnostic_probe_relationship}).
 *
 * <p>Resolving <em>which one</em> objective to prefer when more than one candidate exists (e.g. a
 * skill with two curriculum prerequisites, or two published {@code ROOT_CAUSE_PROBE} rows from the
 * same source objective) is deliberately out of scope for this foundation -- the repository picks
 * the first in a fixed, documented order (skill/objective {@code display_order}, or relationship
 * {@code id}) rather than the resolver fanning out across several. Considering multiple target
 * objectives together is a real future capability, not decided here.
 *
 * @param objectiveId the related objective this hypothesis is about
 * @param authorizingRelationshipId see {@link DiagnosticHypothesis#authorizingRelationshipId()};
 *     {@code null} for the two relationship types with no row of their own
 */
public record ProbeTargetObjective(UUID objectiveId, UUID authorizingRelationshipId) {
}
