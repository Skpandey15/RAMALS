package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * H4b foundation (M2-ADR-024): the outcome of one probe answer, interpreted against the hypothesis
 * it was selected to test. Pure and computed on read, the same way {@code SkillMasterySignal} is --
 * nothing here is persisted by this layer, and nothing here writes {@code ledger.mastery_snapshot},
 * {@code MasteryStatus}, or any frozen mastery policy. Mastery stays owned by the mastery
 * engine/evidence pipeline exactly as it already is; this is a separate, non-authoritative reading
 * of the same {@code core.assessment_response} row every scorer already writes.
 *
 * @param hypothesis the hypothesis this probe was selected to test
 * @param probeItemVersionId the item actually answered
 * @param isCorrect the scoring fact this evidence was read from, unchanged
 * @param outcome {@link HypothesisEvidenceOutcome#classify}'s result for this response
 */
public record HypothesisEvidence(
    DiagnosticHypothesis hypothesis,
    UUID probeItemVersionId,
    boolean isCorrect,
    HypothesisEvidenceOutcome outcome) {
}
