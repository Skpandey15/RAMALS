package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * M2-ADR-034 Amendment 4: the complete outcome of one exact-replay attempt.
 *
 * @param destinationAttemptId the attempt replay was requested for
 * @param status see {@link DiagnosticSelectionV6ReplayStatus}
 * @param recomputedDecision the decision recomputed from the persisted working set -- {@code null}
 *     unless {@code status} is {@code VERIFIED} or {@code INTEGRITY_FAILURE}. Never itself the
 *     persisted metadata -- always the result of actually re-running {@code
 *     HYPOTHESIS_UNCERTAINTY_V1} / {@code HYPOTHESIS_DISCRIMINATION_V1} / the frozen V6 activation
 *     rules against the persisted working set.
 * @param verifiedProbeProvenance the historical final probe selection (M2-ADR-034 Amendment 4 sec
 *     S), read back from the already-exact, immutable {@code core.diagnostic_probe_provenance}
 *     table -- present whenever a hypothesis-driven probe was actually placed in the packet,
 *     whether {@code V6} itself activated and chose it or {@code V6} fell back and {@code V5}'s own
 *     resolution chose it. {@code null} when no such probe exists for this attempt, or when {@code
 *     status} is not {@code VERIFIED}.
 * @param integrityFailureDetail a human-readable description of the divergence -- {@code null}
 *     unless {@code status == INTEGRITY_FAILURE}
 */
public record DiagnosticSelectionV6ReplayResult(
    UUID destinationAttemptId,
    DiagnosticSelectionV6ReplayStatus status,
    HypothesisDiscriminationDiagnosticSelector.Decision recomputedDecision,
    ProbeProvenance verifiedProbeProvenance,
    String integrityFailureDetail) {

  static DiagnosticSelectionV6ReplayResult notAvailable(UUID destinationAttemptId) {
    return new DiagnosticSelectionV6ReplayResult(
        destinationAttemptId, DiagnosticSelectionV6ReplayStatus.NOT_AVAILABLE, null, null, null);
  }

  static DiagnosticSelectionV6ReplayResult unsupportedSnapshotVersion(
      UUID destinationAttemptId, String detail) {
    return new DiagnosticSelectionV6ReplayResult(
        destinationAttemptId, DiagnosticSelectionV6ReplayStatus.UNSUPPORTED_SNAPSHOT_VERSION, null, null, detail);
  }

  static DiagnosticSelectionV6ReplayResult verified(
      UUID destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.Decision recomputedDecision,
      ProbeProvenance verifiedProbeProvenance) {
    return new DiagnosticSelectionV6ReplayResult(
        destinationAttemptId, DiagnosticSelectionV6ReplayStatus.VERIFIED, recomputedDecision,
        verifiedProbeProvenance, null);
  }

  static DiagnosticSelectionV6ReplayResult integrityFailure(
      UUID destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.Decision recomputedDecision,
      String detail) {
    return new DiagnosticSelectionV6ReplayResult(
        destinationAttemptId, DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE, recomputedDecision, null, detail);
  }
}
