package io.ramals.learningplatform.assessment;

import org.springframework.stereotype.Component;

/**
 * LONGITUDINAL_EVIDENCE_V1 (M2-ADR-030): a pure, deterministic sign/existence classifier over one
 * misconception's post-baseline {@code SUPPORTING}/{@code CONTRADICTORY}/{@code INCONCLUSIVE} evidence
 * counts -- {@code E_post}, the set difference between all {@code core.misconception_evidence_
 * observation} rows for one {@code (learner, misconception)} pair and the baseline snapshot's own
 * permanent cited provenance set. No database access; a call with the same three counts always
 * produces the same {@link LongitudinalEvidenceState}.
 *
 * <p><b>Deliberately not a numeric-threshold policy.</b> Unlike {@link DiagnosticConfidenceCalculatorV1}
 * (which this class never invokes, reuses, or modifies), classification here depends only on whether
 * each count is zero or non-zero -- magnitude never changes the result. This answers a genuinely
 * different question than the calculator does: "what did evidence recorded after a fixed point say,"
 * never "how strong is the lifetime cumulative case." Reusing the calculator's band vocabulary for
 * this question would silently overload its meaning -- a heavily-supported baseline can absorb real
 * post-baseline contradictory evidence without its cumulative band ever moving (e.g. baseline
 * {@code S=10,C=0} stays {@code HIGH} after two new contradictory observations, {@code 10 > 3*2}),
 * which is exactly the failure mode a separate, order-independent, magnitude-independent classifier
 * avoids.
 *
 * <p><b>{@code POLICY_VERSION} deliberately participates in {@code EngineVersionFreezeTests}.</b> Its
 * name ends in {@code VERSION} (unlike {@code MisconceptionEvidenceCaptureService.POLICY}, which was
 * deliberately named otherwise to opt out of that test's mechanical scan) because this policy's
 * output is the final, directly learner/admin-facing interpretation H7 exposes -- there is no further
 * downstream engine translating it, so it is frozen like every other governed policy in this
 * codebase.
 */
@Component
public class LongitudinalEvidencePolicyV1 {

  public static final String POLICY_VERSION = "LONGITUDINAL_EVIDENCE_V1";

  public LongitudinalEvidenceState classify(
      int supportingDelta, int contradictoryDelta, int inconclusiveDelta) {
    if (supportingDelta == 0 && contradictoryDelta == 0 && inconclusiveDelta == 0) {
      return LongitudinalEvidenceState.NO_LATER_EVIDENCE;
    }
    if (supportingDelta == 0 && contradictoryDelta == 0) {
      return LongitudinalEvidenceState.LATER_INCONCLUSIVE_ONLY;
    }
    if (contradictoryDelta == 0) {
      return LongitudinalEvidenceState.LATER_SUPPORT_ONLY;
    }
    if (supportingDelta == 0) {
      return LongitudinalEvidenceState.LATER_CONTRADICTION_ONLY;
    }
    return LongitudinalEvidenceState.LATER_MIXED_EVIDENCE;
  }
}
