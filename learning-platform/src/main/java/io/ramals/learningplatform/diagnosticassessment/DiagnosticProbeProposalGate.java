package io.ramals.learningplatform.diagnosticassessment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The deterministic acceptance boundary for advisory diagnostic-probe proposals (M2-ADR-032 4, 14,
 * 20).
 *
 * <p><b>Agents recommend; deterministic services decide.</b> The proposal that reaches this gate is
 * non-deterministic model output. This gate is not: given one proposal and Java's own authoritative
 * state, {@link #evaluate} always returns the same {@code accepted} flag and the same ordered
 * reasons. Its behaviour is frozen under {@link #POLICY_VERSION} and pinned by
 * {@code EngineVersionFreezeTests}.
 *
 * <p>It implements every one of M2-ADR-032 4's fifteen mandatory checks, plus the semantic-safety
 * rules of 3/8/9 (no diagnosis, no probability language, no resolution terminology), independently
 * of anything the AI asserted about itself. Every check is <b>fail-closed</b> (14): a validation
 * that cannot be completed is a rejection, never a best-effort acceptance and never a fallback to a
 * broader authority.
 *
 * <p>What it deliberately does NOT do (M2-ADR-032 6/13): decide whether the recommended probe is
 * <em>eligible to execute</em>. That is deterministic Java selection policy's job
 * (H4b / {@code DIAGNOSTIC_SELECTION_V1}-{@code V5}), untouched here. {@code accepted} means only
 * "well-formed, evidence-grounded, in scope" -- an accepted recommendation may inform a subsequent
 * diagnostic interaction; it never mutates an assessment attempt in progress and never runs
 * anything.
 *
 * <p>No model call, no mutable state, no authoritative write. It returns a decision; recording it
 * and acting on it belong to {@link DiagnosticProbeProposalService} and, later, to a separately
 * reviewed reasoning/integration PR.
 */
public final class DiagnosticProbeProposalGate {

  /**
   * The frozen policy identifier for this gate's decision behaviour (M2-ADR-032 20).
   *
   * <p>Every acceptance or rejection is reproducible from persisted, versioned deterministic inputs
   * under this identifier. Changing any check, the terminology list, or the reason mapping requires
   * a new identifier ({@code _V2}) and an ADR -- never an in-place edit.
   */
  public static final String POLICY_VERSION = "DIAGNOSTIC_PROBE_PROPOSAL_GATE_V1";

  /**
   * Substrings a rationale may never contain (M2-ADR-032 8/9; M2-ADR-030 G). Lower-cased,
   * whitespace-collapsed comparison. Part of {@link #POLICY_VERSION}'s frozen behaviour: an
   * evidence-acquisition recommendation states that more evidence would help, never a probability,
   * a percentage, a comparative judgement between misconceptions, or a resolution claim.
   */
  static final List<String> FORBIDDEN_TERMINOLOGY =
      List.of(
          "probability",
          "probably",
          "percent",
          "percentage",
          "%",
          "likely",
          "likelihood",
          "confirms",
          "confirmed",
          "verified",
          "resolved",
          "cured",
          "recurrence",
          "recurred",
          "regression",
          "regressed",
          "reversal",
          "root cause",
          "rootcause",
          "definitely",
          "certainly",
          "proven",
          "is diagnosed",
          "has the misconception",
          "holds the misconception",
          "the learner has this",
          "the learner has the misconception");

  /**
   * Evaluates a proposal against the exact authoritative context the interaction that produced it
   * was bound to. Never re-derives that context now: a proposal must be judged against what the
   * interaction was actually authorized for.
   */
  public DiagnosticProbeProposalGateResult evaluate(
      DiagnosticProbeProposal proposal,
      DiagnosticProbeProposalContext context,
      DiagnosticProbeTargetPort targetPort) {

    if (proposal == null || context == null || targetPort == null) {
      return rejected(
          EnumSet.of(DiagnosticProbeProposalGateReason.VALIDATION_UNAVAILABLE), Set.of(), null);
    }

    Set<DiagnosticProbeProposalGateReason> reasons =
        EnumSet.noneOf(DiagnosticProbeProposalGateReason.class);

    // 4.2/4.3/4.4/14 -- the context itself must carry enough authoritative binding to judge at all.
    if (!context.isComplete()) {
      reasons.add(DiagnosticProbeProposalGateReason.VALIDATION_UNAVAILABLE);
    }

    // 4.1 -- contract/schema version this deployed gate accepts.
    if (!DiagnosticProbeProposal.CONTRACT_VERSION.equals(proposal.contractVersion())) {
      reasons.add(DiagnosticProbeProposalGateReason.PROPOSAL_CONTRACT_VERSION_UNSUPPORTED);
    }

    // 4.3 -- the proposal concerns the interaction that produced it, not a stale or unrelated one.
    // The proposal's echoed interactionId is compared to the interaction's own; it never supplies it.
    if (context.interactionId() != null
        && !context.interactionId().equals(proposal.interactionId())) {
      reasons.add(DiagnosticProbeProposalGateReason.INTERACTION_BINDING_MISMATCH);
    }

    // 4.4 -- domain match, exact. A proposal never widens the interaction's authorized domain.
    if (context.domain() != null && !context.domain().equals(proposal.domain())) {
      reasons.add(DiagnosticProbeProposalGateReason.DOMAIN_BINDING_MISMATCH);
    }

    // 4.6 / 12 -- the target misconception must be one actually exposed to this interaction.
    UUID misconceptionId = proposal.targetMisconceptionId();
    if (!context.allowedMisconceptionIds().contains(misconceptionId)) {
      reasons.add(DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_OUT_OF_SCOPE);
    }

    // 4.5/4.7 -- independent authoritative lookup, regardless of what the AI claimed.
    Optional<DiagnosticProbeTargetPort.ResolvedMisconception> resolved;
    try {
      resolved = targetPort.findMisconception(misconceptionId);
    } catch (RuntimeException lookupFailed) {
      resolved = Optional.empty();
      reasons.add(DiagnosticProbeProposalGateReason.VALIDATION_UNAVAILABLE);
    }
    if (resolved.isEmpty()) {
      reasons.add(DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_NOT_FOUND);
    } else {
      DiagnosticProbeTargetPort.ResolvedMisconception m = resolved.get();
      if (!m.published()) {
        reasons.add(DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_NOT_PUBLISHED);
      }
      // 4.7 / 10 -- the cited targetNode must be the misconception's real exclusive-arc target
      // (M2-ADR-026 4), not a node chosen freely.
      reasons.addAll(checkArc(proposal.targetNode(), m, targetPort));
    }

    // 4.8 / 11 -- E_proposed subset of E_allowed. A citation outside the exact governed set supplied
    // to this interaction is rejected outright, independently of the prompt. This transitively
    // satisfies 4.9 and 4.10: E_allowed is the interaction's own learner- and domain-scoped
    // governed evidence set (the MCP reads it actually received), so an in-set reference already
    // belongs to the authorized learner and concerns the referenced misconception/domain.
    for (String ref : proposal.evidenceRefs()) {
      if (!context.allowedEvidenceRefs().contains(ref)) {
        reasons.add(DiagnosticProbeProposalGateReason.EVIDENCE_REFERENCE_NOT_IN_CONTEXT);
        break;
      }
    }

    // 4.13 -- naming a specific probe object is never authorization to run it; when named, the
    // reference must be one authorized for this interaction. Eligibility itself is not decided here.
    if (proposal.candidateProbeRef() != null
        && !context.allowedCandidateProbeRefs().contains(proposal.candidateProbeRef())) {
      reasons.add(DiagnosticProbeProposalGateReason.CANDIDATE_PROBE_REFERENCE_NOT_AUTHORIZED);
    }

    // 4.13 -- the recommendation may not imply an authority the interaction was not delegated
    // (M2-ADR-031). Fine-grained per-capability enforcement already ran upstream at the MCP
    // boundary; here the gate refuses a recommendation from an interaction delegated nothing.
    if (context.delegatedCapabilities().isEmpty()) {
      reasons.add(DiagnosticProbeProposalGateReason.CAPABILITY_OUT_OF_SCOPE);
    }

    // 4.12 -- an independent final sweep: every identifier the proposal carries must be accounted
    // for by an authorized/allowed set. Redundant with the specific checks above by design
    // (defence in depth); it exists so an identifier can never slip through because an earlier
    // check was skipped.
    if (carriesUnauthorizedIdentifier(proposal, context, resolved)) {
      reasons.add(DiagnosticProbeProposalGateReason.UNAUTHORIZED_IDENTIFIER_PRESENT);
    }

    // 3 / 8 / 9 -- semantic safety: the rationale must not restate governed evidence strength as a
    // probability, compare misconceptions, or claim resolution.
    if (containsForbiddenTerminology(proposal.rationale())) {
      reasons.add(DiagnosticProbeProposalGateReason.RATIONALE_FORBIDDEN_TERMINOLOGY);
    }

    Set<String> referenced = new TreeSet<>(proposal.evidenceRefs());
    if (reasons.isEmpty()) {
      // 4.15 -- acceptance is still Java's own; the caller decides whether and how it is used.
      return new DiagnosticProbeProposalGateResult(
          true,
          List.of(DiagnosticProbeProposalGateReason.ACCEPTED),
          referenced,
          misconceptionId,
          POLICY_VERSION);
    }
    return rejected(reasons, referenced, misconceptionId);
  }

  private static Set<DiagnosticProbeProposalGateReason> checkArc(
      DiagnosticProbeProposal.TargetNode cited,
      DiagnosticProbeTargetPort.ResolvedMisconception m,
      DiagnosticProbeTargetPort targetPort) {
    Set<DiagnosticProbeProposalGateReason> reasons =
        EnumSet.noneOf(DiagnosticProbeProposalGateReason.class);

    if (m.targetObjectiveId() != null) {
      // The misconception targets a LearningObjective directly -- no diagnostic node is involved.
      boolean matches =
          cited.kind() == DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE
              && m.targetObjectiveId().equals(cited.id());
      if (!matches) {
        reasons.add(DiagnosticProbeProposalGateReason.TARGET_NODE_ARC_MISMATCH);
      }
      return reasons;
    }

    if (m.targetDiagnosticNodeId() != null) {
      if (!m.targetDiagnosticNodeId().equals(cited.id())) {
        reasons.add(DiagnosticProbeProposalGateReason.TARGET_NODE_ARC_MISMATCH);
        return reasons;
      }
      Optional<DiagnosticProbeProposal.TargetNode.Kind> actualKind;
      try {
        actualKind = targetPort.findDiagnosticNodeKind(cited.id());
      } catch (RuntimeException lookupFailed) {
        reasons.add(DiagnosticProbeProposalGateReason.VALIDATION_UNAVAILABLE);
        return reasons;
      }
      if (actualKind.isEmpty()) {
        reasons.add(DiagnosticProbeProposalGateReason.TARGET_NODE_NOT_FOUND);
      } else if (actualKind.get() != cited.kind()) {
        reasons.add(DiagnosticProbeProposalGateReason.TARGET_NODE_ARC_MISMATCH);
      }
      return reasons;
    }

    // Neither arm set -- the row violates M2-ADR-026's exclusive arc. Fail closed rather than guess.
    reasons.add(DiagnosticProbeProposalGateReason.VALIDATION_UNAVAILABLE);
    return reasons;
  }

  private static boolean carriesUnauthorizedIdentifier(
      DiagnosticProbeProposal proposal,
      DiagnosticProbeProposalContext context,
      Optional<DiagnosticProbeTargetPort.ResolvedMisconception> resolved) {

    if (!context.allowedMisconceptionIds().contains(proposal.targetMisconceptionId())) {
      return true;
    }
    for (String ref : proposal.evidenceRefs()) {
      if (!context.allowedEvidenceRefs().contains(ref)) {
        return true;
      }
    }
    if (proposal.candidateProbeRef() != null
        && !context.allowedCandidateProbeRefs().contains(proposal.candidateProbeRef())) {
      return true;
    }
    // targetNode.id is authorized only by matching the misconception's own arc target.
    UUID citedNodeId = proposal.targetNode() == null ? null : proposal.targetNode().id();
    if (citedNodeId != null && resolved.isPresent()) {
      DiagnosticProbeTargetPort.ResolvedMisconception m = resolved.get();
      boolean matchesArc =
          citedNodeId.equals(m.targetObjectiveId())
              || citedNodeId.equals(m.targetDiagnosticNodeId());
      return !matchesArc;
    }
    return false;
  }

  static boolean containsForbiddenTerminology(String rationale) {
    if (rationale == null) {
      return false;
    }
    String normalized = rationale.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    for (String banned : FORBIDDEN_TERMINOLOGY) {
      if (normalized.contains(banned)) {
        return true;
      }
    }
    return false;
  }

  private static DiagnosticProbeProposalGateResult rejected(
      Set<DiagnosticProbeProposalGateReason> reasons, Set<String> referenced, UUID misconceptionId) {
    List<DiagnosticProbeProposalGateReason> ordered = new ArrayList<>(reasons);
    ordered.sort(Comparator.comparing(Enum::name));
    return new DiagnosticProbeProposalGateResult(
        false, ordered, referenced, misconceptionId, POLICY_VERSION);
  }
}
