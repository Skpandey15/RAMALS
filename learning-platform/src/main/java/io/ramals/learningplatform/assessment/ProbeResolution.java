package io.ramals.learningplatform.assessment;

import java.util.List;

/**
 * H4b foundation (M2-ADR-024): the full, auditable result of one {@link ProbeRelationshipResolver}
 * call -- what was tried, what it found, and why.
 *
 * @param outcome what happened -- see {@link ProbeResolutionOutcome}
 * @param hypothesis {@code null} iff {@code outcome == NO_RELATIONSHIP_DEFINED}; present (but with
 *     an empty {@code candidates} list) for {@code RELATIONSHIP_DEFINED_BUT_NO_ITEMS} and
 *     {@code ALL_CANDIDATES_ALREADY_EXPOSED}, so the hypothesis that was raised is still auditable
 *     even when nothing could be served for it
 * @param candidates every unseen candidate for the hypothesis, deterministically ordered
 *     (item {@code display_order}, then {@code item_code}); empty unless
 *     {@code outcome == CANDIDATES_AVAILABLE}
 */
public record ProbeResolution(
    ProbeResolutionOutcome outcome, DiagnosticHypothesis hypothesis, List<ProbeCandidateItem> candidates) {
}
