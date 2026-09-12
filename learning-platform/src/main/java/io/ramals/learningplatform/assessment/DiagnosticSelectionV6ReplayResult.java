package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * M2-ADR-034 Amendment 4: the complete outcome of one exact-replay attempt.
 *
 * @param destinationAttemptId the attempt replay was requested for
 * @param status see {@link DiagnosticSelectionV6ReplayStatus}
 * @param recomputedDecision the decision recomputed from the persisted working set -- {@code null}
 *     iff {@code status == NOT_AVAILABLE}. Never itself the persisted metadata -- always the result
 *     of actually re-running {@code HYPOTHESIS_UNCERTAINTY_V1} / {@code HYPOTHESIS_DISCRIMINATION_V1}
 *     / the frozen V6 activation rules against the persisted working set.
 * @param integrityFailureDetail a human-readable description of the divergence -- {@code null}
 *     unless {@code status == INTEGRITY_FAILURE}
 */
public record DiagnosticSelectionV6ReplayResult(
    UUID destinationAttemptId,
    DiagnosticSelectionV6ReplayStatus status,
    HypothesisDiscriminationDiagnosticSelector.Decision recomputedDecision,
    String integrityFailureDetail) {

  static DiagnosticSelectionV6ReplayResult notAvailable(UUID destinationAttemptId) {
    return new DiagnosticSelectionV6ReplayResult(
        destinationAttemptId, DiagnosticSelectionV6ReplayStatus.NOT_AVAILABLE, null, null);
  }

  static DiagnosticSelectionV6ReplayResult verified(
      UUID destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.Decision recomputedDecision) {
    return new DiagnosticSelectionV6ReplayResult(
        destinationAttemptId, DiagnosticSelectionV6ReplayStatus.VERIFIED, recomputedDecision, null);
  }

  static DiagnosticSelectionV6ReplayResult integrityFailure(
      UUID destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.Decision recomputedDecision,
      String detail) {
    return new DiagnosticSelectionV6ReplayResult(
        destinationAttemptId, DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE, recomputedDecision, detail);
  }
}
