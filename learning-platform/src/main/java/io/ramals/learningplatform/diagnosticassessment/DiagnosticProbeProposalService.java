package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses, gates and records one advisory diagnostic-probe proposal (M2-ADR-032 step 2).
 *
 * <p><b>The AI proposal is never a hard dependency of diagnostic progression.</b> A caller on the
 * deterministic diagnostic path treats any outcome other than {@link Outcome.Status#ACCEPTED} --
 * including no proposal at all, a malformed one, a rejected one, or a thrown persistence failure --
 * as "there is no advisory recommendation" and proceeds exactly as it would without one. This
 * service therefore:
 *
 * <ul>
 *   <li>returns {@link Outcome.Status#ABSENT} and writes nothing when the agent produced no proposal
 *       (unavailable, timed out, or degraded past MCP);
 *   <li>returns {@link Outcome.Status#MALFORMED} and records an audit row when a payload arrived but
 *       could not be read as the v1 contract;
 *   <li>returns {@link Outcome.Status#REJECTED} with stable reason codes, and an audit row, when the
 *       deterministic gate refused it;
 *   <li>returns {@link Outcome.Status#ACCEPTED} only when {@link DiagnosticProbeProposalGate} passed
 *       every check -- and even then, "accepted" means well-formed, evidence-grounded and in scope,
 *       never that a probe is eligible or will run (M2-ADR-032 6/13).
 * </ul>
 *
 * <p>Idempotent on the proposal identity: a retry returns the persisted verdict rather than
 * re-deciding. The gate is a pure function, so re-deciding would produce the same result anyway;
 * the lookup just avoids a duplicate audit row and a wasted evaluation.
 *
 * <p>Writes no mastery, no evidence, no progression, no DIAGNOSTIC_SELECTION state. It records a
 * decision and nothing else.
 */
public class DiagnosticProbeProposalService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticProbeProposalService.class);

  private final DiagnosticProbeProposalGate gate;
  private final DiagnosticProbeTargetPort targetPort;
  private final DiagnosticProbeProposalDecisionPort decisions;

  public DiagnosticProbeProposalService(
      DiagnosticProbeProposalGate gate,
      DiagnosticProbeTargetPort targetPort,
      DiagnosticProbeProposalDecisionPort decisions) {
    this.gate = gate;
    this.targetPort = targetPort;
    this.decisions = decisions;
  }

  /**
   * Evaluates one proposal returned by the diagnostic-assessment agent.
   *
   * @param envelope the agent's proposal envelope, or {@code null}/absent when the agent produced
   *     nothing -- in which case the deterministic diagnostic path is unaffected and nothing is
   *     written
   * @param context the authoritative context the interaction was bound to (M2-ADR-032 4); never
   *     derived from the proposal
   * @param correlation the learner action and distributed trace this evaluation belongs to
   */
  public Outcome evaluate(
      AiProposalEnvelope envelope, DiagnosticProbeProposalContext context, Correlation correlation) {

    if (envelope == null || envelope.proposal() == null || envelope.proposal().isEmpty()) {
      return Outcome.absent();
    }
    Correlation safeCorrelation = correlation == null ? Correlation.absent() : correlation;

    Optional<DiagnosticProbeProposalDecisionPort.RecordedProbeDecision> already =
        decisions.findByProposalId(envelope.proposalId());
    if (already.isPresent()) {
      return Outcome.fromRecorded(already.get());
    }

    DiagnosticProbeProposal proposal;
    try {
      proposal =
          DiagnosticProbeProposal.parse(
              envelope.proposal(),
              envelope.proposalId(),
              requestIdOf(envelope, safeCorrelation),
              envelope.agentRunId());
    } catch (DiagnosticProbeProposal.MalformedProbeProposalException malformed) {
      DiagnosticProbeProposalDecision record =
          malformedRecord(envelope, context, safeCorrelation, malformed.reasonCode());
      persist(record);
      log(envelope, Outcome.Status.MALFORMED, List.of(), malformed.reasonCode(), safeCorrelation);
      return new Outcome(
          Outcome.Status.MALFORMED,
          List.of(DiagnosticProbeProposalGateReason.PROPOSAL_MALFORMED),
          envelope.proposalId(),
          malformed.reasonCode(),
          DiagnosticProbeProposalGate.POLICY_VERSION);
    }

    DiagnosticProbeProposalGateResult result = gate.evaluate(proposal, context, targetPort);
    DiagnosticProbeProposalDecision record =
        gatedRecord(envelope, proposal, context, safeCorrelation, result);
    persist(record);

    Outcome.Status status =
        result.accepted() ? Outcome.Status.ACCEPTED : Outcome.Status.REJECTED;
    log(envelope, status, result.reasons(), null, safeCorrelation);
    return new Outcome(
        status, result.reasons(), proposal.proposalId(), null, result.policyVersion());
  }

  private void persist(DiagnosticProbeProposalDecision record) {
    try {
      decisions.append(record);
    } catch (RuntimeException persistenceFailure) {
      // The audit could not be written. Surface it -- a silent swallow would be a false clean
      // record -- but as a dedicated type a caller on the diagnostic path treats the same as any
      // other non-acceptance: no advisory recommendation, proceed deterministically.
      throw new DiagnosticProbeProposalPersistenceException(
          "could not record the advisory diagnostic-probe proposal decision", persistenceFailure);
    }
  }

  private static String requestIdOf(AiProposalEnvelope envelope, Correlation correlation) {
    // The envelope has no requestId of its own; the diagnostic-assessment path binds it from the
    // interaction. Fall back to the proposalId only so a synthetic test envelope still parses.
    return correlation.requestId() != null && !correlation.requestId().isBlank()
        ? correlation.requestId()
        : envelope.proposalId();
  }

  private static DiagnosticProbeProposalDecision malformedRecord(
      AiProposalEnvelope envelope,
      DiagnosticProbeProposalContext context,
      Correlation correlation,
      String parserReasonCode) {
    return new DiagnosticProbeProposalDecision(
        envelope.proposalId(),
        requestIdOf(envelope, correlation),
        envelope.agentRunId(),
        context.interactionId(),
        correlation.traceId(),
        context.authoritativeLearnerId(),
        context.domain(),
        DiagnosticProbeProposal.CONTRACT_VERSION,
        DiagnosticProbeProposalGate.POLICY_VERSION,
        false,
        List.of(DiagnosticProbeProposalGateReason.PROPOSAL_MALFORMED.name()),
        parserReasonCode,
        null,
        null,
        null,
        null,
        null,
        List.copyOf(new java.util.TreeSet<>(context.allowedEvidenceRefs())),
        List.of(),
        envelope.promptTemplateId(),
        envelope.promptVersion(),
        envelope.modelRoute(),
        envelope.resolvedProvider(),
        envelope.modelId(),
        envelope.routeVersion());
  }

  private static DiagnosticProbeProposalDecision gatedRecord(
      AiProposalEnvelope envelope,
      DiagnosticProbeProposal proposal,
      DiagnosticProbeProposalContext context,
      Correlation correlation,
      DiagnosticProbeProposalGateResult result) {
    return new DiagnosticProbeProposalDecision(
        proposal.proposalId(),
        requestIdOf(envelope, correlation),
        proposal.agentRunId(),
        context.interactionId(),
        correlation.traceId(),
        context.authoritativeLearnerId(),
        context.domain(),
        proposal.contractVersion(),
        result.policyVersion(),
        result.accepted(),
        result.reasons().stream().map(Enum::name).toList(),
        null,
        proposal.targetMisconceptionId(),
        proposal.targetNode() == null ? null : proposal.targetNode().kind().name(),
        proposal.targetNode() == null ? null : proposal.targetNode().id(),
        proposal.probeIntent() == null ? null : proposal.probeIntent().name(),
        proposal.candidateProbeRef(),
        List.copyOf(new java.util.TreeSet<>(context.allowedEvidenceRefs())),
        List.copyOf(new java.util.TreeSet<>(proposal.evidenceRefs())),
        envelope.promptTemplateId(),
        envelope.promptVersion(),
        envelope.modelRoute(),
        envelope.resolvedProvider(),
        envelope.modelId(),
        envelope.routeVersion());
  }

  private static void log(
      AiProposalEnvelope envelope,
      Outcome.Status status,
      List<DiagnosticProbeProposalGateReason> reasons,
      String parserReasonCode,
      Correlation correlation) {
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(correlation.interactionId(), correlation.traceId())) {
      var event = status == Outcome.Status.ACCEPTED ? LOGGER.atInfo() : LOGGER.atWarn();
      event
          .addKeyValue("operation", "ai.diagnosticProbeProposal.gate")
          .addKeyValue("outcome", status.name())
          .addKeyValue("reasonCodes", reasons.stream().map(Enum::name).toList())
          .addKeyValue("parserReasonCode", parserReasonCode)
          .addKeyValue("proposalId", envelope.proposalId())
          .addKeyValue("agentRunId", envelope.agentRunId())
          .addKeyValue("policyVersion", DiagnosticProbeProposalGate.POLICY_VERSION)
          .log("advisory diagnostic-probe proposal gate decided");
    }
  }

  /** The learner action and distributed trace one evaluation belongs to (M2-ADR-032 19). */
  public record Correlation(String interactionId, String traceId, String requestId) {
    public static Correlation absent() {
      return new Correlation(null, null, null);
    }
  }

  /** The decision, and the identity needed to find the audit row behind it. */
  public record Outcome(
      Status status,
      List<DiagnosticProbeProposalGateReason> reasons,
      String proposalId,
      String parserReasonCode,
      String policyVersion) {

    public Outcome {
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public enum Status {
      /** Every gate check passed. Well-formed, evidence-grounded, in scope -- not "eligible to run". */
      ACCEPTED,
      /** The deterministic gate refused it; {@link #reasons()} carries the stable codes. */
      REJECTED,
      /** A payload arrived but could not be read as the v1 contract. */
      MALFORMED,
      /** No proposal was produced. Nothing recorded; the deterministic diagnostic path is unaffected. */
      ABSENT
    }

    static Outcome absent() {
      return new Outcome(Status.ABSENT, List.of(), null, null, null);
    }

    static Outcome fromRecorded(DiagnosticProbeProposalDecisionPort.RecordedProbeDecision recorded) {
      Status status;
      List<DiagnosticProbeProposalGateReason> reasons;
      if (recorded.parserReasonCode() != null) {
        status = Status.MALFORMED;
        reasons = List.of(DiagnosticProbeProposalGateReason.PROPOSAL_MALFORMED);
      } else if (recorded.accepted()) {
        status = Status.ACCEPTED;
        reasons = List.of(DiagnosticProbeProposalGateReason.ACCEPTED);
      } else {
        status = Status.REJECTED;
        reasons = mapReasons(recorded.reasonCodes());
      }
      return new Outcome(
          status,
          reasons,
          recorded.proposalId(),
          recorded.parserReasonCode(),
          recorded.policyVersion());
    }

    private static List<DiagnosticProbeProposalGateReason> mapReasons(List<String> codes) {
      java.util.List<DiagnosticProbeProposalGateReason> mapped = new java.util.ArrayList<>();
      for (String code : codes) {
        try {
          mapped.add(DiagnosticProbeProposalGateReason.valueOf(code));
        } catch (IllegalArgumentException unknown) {
          // A code this build does not recognise stays out of the typed list rather than failing
          // a recovery read; the persisted row remains the source of truth.
        }
      }
      return List.copyOf(mapped);
    }

    public boolean acceptedAndUsable() {
      return status == Status.ACCEPTED;
    }
  }

  /** A real persistence failure recording a decision. Non-acceptance to a diagnostic-path caller. */
  public static final class DiagnosticProbeProposalPersistenceException extends RuntimeException {
    DiagnosticProbeProposalPersistenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
