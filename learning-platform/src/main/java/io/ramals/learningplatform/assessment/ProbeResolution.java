package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): the full, auditable result of one {@link ProbeRelationshipResolver}
 * call -- what was tried, what it found, and why.
 *
 * @param outcome what happened -- see {@link ProbeResolutionOutcome}
 * @param hypothesis {@code null} iff {@code outcome == NO_RELATIONSHIP_DEFINED} or
 *     {@code AMBIGUOUS_TARGET_OBJECTIVE} -- neither has a single target objective to name one
 *     against. Present (but with an empty {@code candidates} list) for
 *     {@code RELATIONSHIP_DEFINED_BUT_NO_ITEMS} and {@code ALL_CANDIDATES_ALREADY_EXPOSED}, so the
 *     hypothesis that was raised is still auditable even when nothing could be served for it
 * @param candidates every unseen candidate for the hypothesis, deterministically ordered
 *     (item {@code display_order}, then {@code item_code}); empty unless
 *     {@code outcome == CANDIDATES_AVAILABLE}
 * @param ambiguousTargetObjectiveIds every candidate target objective that made the choice
 *     ambiguous, in the order the repository resolved them; empty unless
 *     {@code outcome == AMBIGUOUS_TARGET_OBJECTIVE}. This is what makes the ambiguity itself
 *     auditable -- see M2-ADR-024's amendment on surfacing rather than arbitrating ambiguity.
 */
public record ProbeResolution(
    ProbeResolutionOutcome outcome,
    DiagnosticHypothesis hypothesis,
    List<ProbeCandidateItem> candidates,
    List<UUID> ambiguousTargetObjectiveIds) {
}
