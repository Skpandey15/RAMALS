package io.ramals.learningplatform.diagnosticassessment;

import java.util.Optional;
import java.util.UUID;

/**
 * The authoritative-state lookups {@link DiagnosticProbeProposalGate} needs to validate a
 * proposal's target, independently of anything the AI asserted about it (M2-ADR-032 4.5-4.7, 12).
 *
 * <p>Read-only by contract. An implementation reads {@code core.misconception} and
 * {@code core.diagnostic_node} (M2-ADR-026, migrations {@code V057}); it never writes, and it never
 * touches mastery, G2/G3 evidence/confidence, progression, or DIAGNOSTIC_SELECTION state.
 *
 * <p>A lookup that cannot be completed returns {@link Optional#empty()} for the misconception (the
 * gate then fails closed with {@link DiagnosticProbeProposalGateReason#TARGET_MISCONCEPTION_NOT_FOUND}
 * or {@link DiagnosticProbeProposalGateReason#VALIDATION_UNAVAILABLE}); it never guesses.
 */
public interface DiagnosticProbeTargetPort {

  /**
   * The authoritative facts about one {@code core.misconception} row, or {@link Optional#empty()}
   * when no such row exists.
   */
  Optional<ResolvedMisconception> findMisconception(UUID misconceptionId);

  /**
   * The node type of one {@code core.diagnostic_node} row, or {@link Optional#empty()} when no such
   * row exists. Used only to confirm the proposal's cited {@code targetNode.kind} matches reality.
   */
  Optional<DiagnosticProbeProposal.TargetNode.Kind> findDiagnosticNodeKind(UUID nodeId);

  /**
   * A misconception's authoritative target, exactly as M2-ADR-026's DB-enforced exclusive arc
   * defines it: {@code published} status, and exactly one of an objective id or a diagnostic-node
   * id.
   */
  record ResolvedMisconception(
      UUID id, boolean published, UUID targetObjectiveId, UUID targetDiagnosticNodeId) {}
}
