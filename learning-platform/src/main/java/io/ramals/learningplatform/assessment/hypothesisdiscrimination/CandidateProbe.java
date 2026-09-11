package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import java.util.UUID;

/**
 * One candidate probe {@code HYPOTHESIS_DISCRIMINATION_V1} may score (M2-ADR-034 Amendment 2 §E).
 * Every legitimate instance originates from the same deterministic, already-governed resolution
 * {@code DIAGNOSTIC_SELECTION_V5} already uses ({@code ProbeRelationshipResolver} / {@code
 * ProbeRelationshipService}) -- this type creates no eligibility of its own, and an accepted
 * M2-ADR-032 advisory proposal is never a legitimate source of one (Amendment 2 §E/§S).
 *
 * <p>Deliberately no compact-constructor validation -- like {@link DiagnosticHypothesis}, a
 * malformed instance (a {@code null} field) must be constructible so {@link
 * HypothesisDiscriminationCalculatorV1}'s own fail-closed validation, not a constructor throw, is
 * what produces {@link HypothesisDiscriminationReasonCode#MALFORMED_CANDIDATE_PROBE}.
 *
 * @param probeItemVersionId the item this probe would present
 * @param hypothesis which candidate hypothesis this probe targets -- must equal one member of the
 *     owning {@link HypothesisDiscriminationContext}'s {@code baseContext.candidates()} or the
 *     context is refused ({@code PROBE_FOR_UNKNOWN_HYPOTHESIS})
 * @param scoreable from the item's own {@code AssessmentItemType.scoreable()} -- {@code false} is
 *     reachable only through direct construction today (Amendment 2 §D's single-reachable-world
 *     case), since {@code ProbeRelationshipRepository.itemsForObjective} already returns only
 *     verified, scoreable items -- reserved, not omitted, the same discipline {@link
 *     io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome#INCONCLUSIVE} already holds
 */
public record CandidateProbe(UUID probeItemVersionId, DiagnosticHypothesis hypothesis, boolean scoreable) {
}
