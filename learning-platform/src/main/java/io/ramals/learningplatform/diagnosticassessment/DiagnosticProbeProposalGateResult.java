package io.ramals.learningplatform.diagnosticassessment;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The deterministic outcome of {@link DiagnosticProbeProposalGate} for one proposal (M2-ADR-032
 * 4/14/20).
 *
 * <p>Given one proposal and Java's own authoritative state, this result is reproducible: the same
 * inputs always produce the same {@code accepted} flag and the same ordered {@code reasons}. The
 * proposal that produced it is not; that non-determinism stops at the gate (M2-ADR-032 20).
 *
 * <p>{@code accepted} means only that the recommendation is well-formed, evidence-grounded and in
 * scope. It never means a probe is eligible or will run -- that decision belongs to deterministic
 * Java selection policy (H4b / DIAGNOSTIC_SELECTION_V1-V5), which this class does not touch
 * (M2-ADR-032 6/13).
 *
 * @param reasons {@code [ACCEPTED]} when accepted; otherwise every distinct rejection reason, sorted
 *     by name for a stable audit
 * @param referencedEvidenceRefs the cited evidence set, normalized and sorted, ready for immutable
 *     persistence
 * @param policyVersion the gate policy identifier that produced this result
 */
public record DiagnosticProbeProposalGateResult(
    boolean accepted,
    List<DiagnosticProbeProposalGateReason> reasons,
    Set<String> referencedEvidenceRefs,
    UUID referencedMisconceptionId,
    String policyVersion) {

  public DiagnosticProbeProposalGateResult {
    reasons = List.copyOf(reasons);
    referencedEvidenceRefs = Set.copyOf(referencedEvidenceRefs);
  }

  public boolean rejected() {
    return !accepted;
  }
}
