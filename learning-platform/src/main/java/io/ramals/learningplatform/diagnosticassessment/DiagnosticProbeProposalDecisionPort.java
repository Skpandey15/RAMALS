package io.ramals.learningplatform.diagnosticassessment;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for immutable advisory diagnostic-probe proposal decisions (M2-ADR-032 19). */
public interface DiagnosticProbeProposalDecisionPort {

  /**
   * Records one decision. Idempotent on {@code (proposalId, policyVersion)}: a retried evaluation of
   * the identical proposal under the identical gate policy collapses to the row already written and
   * never raises.
   */
  void append(DiagnosticProbeProposalDecision decision);

  /**
   * The decision already recorded for a proposal identity under this gate's current policy version,
   * if there is one. Exists so a retry returns the persisted verdict rather than re-deciding.
   */
  Optional<RecordedProbeDecision> findByProposalId(String proposalId);

  /** A durable decision reduced to what a caller resuming from a retry needs. */
  record RecordedProbeDecision(
      String proposalId,
      String interactionId,
      boolean accepted,
      List<String> reasonCodes,
      String parserReasonCode,
      String policyVersion) {}
}
