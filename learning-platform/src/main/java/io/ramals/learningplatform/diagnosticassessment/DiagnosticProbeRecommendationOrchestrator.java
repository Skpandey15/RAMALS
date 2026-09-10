package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.AiDelegatedCapabilityPolicy;
import io.ramals.learningplatform.ai.AiUnavailableException;
import io.ramals.learningplatform.ai.DelegatedAiContextMinter;
import io.ramals.learningplatform.ai.DelegatedAiExecutionContext;
import io.ramals.learningplatform.ai.DiagnosticProbePort;
import io.ramals.learningplatform.ai.DomainContextAssembler;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Step 3 orchestration for M2-ADR-032: connect bounded AI reasoning to the deterministic gate from
 * PR #266, and do nothing else.
 *
 * <p>Responsibilities, in order, and no others:
 *
 * <ol>
 *   <li>assemble the authoritative {@link DiagnosticProbeProposalContext} from Java's own H6/H7
 *       read services ({@link DiagnosticProbeContextAssembler});
 *   <li>detect the deterministic no-op (no groundable evidence) and return {@code ABSENT} without a
 *       model call (M2-ADR-032 15);
 *   <li>mint the M2-ADR-031 delegated learner-context credential for the AI read;
 *   <li>call the diagnostic-probe AI client;
 *   <li>convert any AI unavailability (transport, timeout, MCP failure, empty response) to a
 *       {@code null} envelope, which {@link DiagnosticProbeProposalService#evaluate} maps to
 *       {@code ABSENT};
 *   <li>route the result through {@link DiagnosticProbeProposalService#evaluate} -- the PR #266
 *       gate, unchanged -- which records the decision;
 *   <li>return the {@link DiagnosticProbeProposalService.Outcome}.
 * </ol>
 *
 * <p>It never invokes diagnostic selection, never executes a probe, never consumes an accepted
 * recommendation, and holds no reference to anything that writes mastery, progression, evidence, or
 * an assessment submission. An {@code ACCEPTED} outcome is recorded in
 * {@code ledger.diagnostic_probe_proposal_decision} and consumed by nothing in step 3.
 */
public class DiagnosticProbeRecommendationOrchestrator {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(DiagnosticProbeRecommendationOrchestrator.class);

  /**
   * The diagnostic-probe reasoning budget. The caller's remaining time still binds when it is
   * shorter (M1-ADR-001). Bounded and small: one advisory read, never a hard dependency.
   */
  public static final long DEFAULT_DEADLINE_MS = 12_000;

  private final DiagnosticProbeContextAssembler assembler;
  private final DomainContextAssembler domainContextAssembler;
  private final DiagnosticProbePort probePort;
  private final DiagnosticProbeProposalService proposalService;
  private final DelegatedAiContextMinter delegatedContextMinter;

  public DiagnosticProbeRecommendationOrchestrator(
      DiagnosticProbeContextAssembler assembler,
      DomainContextAssembler domainContextAssembler,
      DiagnosticProbePort probePort,
      DiagnosticProbeProposalService proposalService,
      DelegatedAiContextMinter delegatedContextMinter) {
    this.assembler = assembler;
    this.domainContextAssembler = domainContextAssembler;
    this.probePort = probePort;
    this.proposalService = proposalService;
    this.delegatedContextMinter = delegatedContextMinter;
  }

  /**
   * Solicits, gates and records one advisory diagnostic-probe recommendation for a future diagnostic
   * interaction concerning {@code (learnerId, domainCode)}.
   *
   * <p>Never throws for an AI-plane failure: the return value is always a
   * {@link DiagnosticProbeProposalService.Outcome}, and any outcome other than {@code ACCEPTED} --
   * including {@code ABSENT} -- leaves diagnostic progression exactly as it was. A genuine
   * persistence failure surfaces as {@link
   * DiagnosticProbeProposalService.DiagnosticProbeProposalPersistenceException}, consistent with
   * PR #266.
   *
   * @param learnerId the interaction's own learner, already resolved from authoritative state
   * @param domainCode the interaction's authorized domain
   * @param interactionId the interaction that is soliciting the recommendation
   * @param requestId the transport-attempt identity for this solicitation
   * @param deadlineMillis the remaining budget for the AI read; {@code <= 0} yields {@code ABSENT}
   */
  public DiagnosticProbeProposalService.Outcome recommendNextProbe(
      UUID learnerId,
      String domainCode,
      String interactionId,
      String requestId,
      long deadlineMillis) {

    DiagnosticProbeContextAssembler.Assembled assembled =
        assembler.assemble(learnerId, domainCode, interactionId);
    DiagnosticProbeProposalContext context = assembled.context();
    DiagnosticProbeProposalService.Correlation correlation =
        new DiagnosticProbeProposalService.Correlation(interactionId, MDC.get("traceId"), requestId);

    // The domain context sent to the model is resolved authoritatively from curriculum state, never
    // from a literal: an unknown domain is one there is nothing to reason about, the same no-op as
    // absent evidence.
    Optional<DomainContext> domainContext =
        domainContextAssembler.forDomain(context.domain());

    if (!assembled.groundable() || deadlineMillis <= 0 || domainContext.isEmpty()) {
      LOGGER
          .atInfo()
          .addKeyValue("operation", "ai.diagnosticProbe.recommend")
          .addKeyValue("outcome", "ABSENT")
          .addKeyValue(
              "reason",
              deadlineMillis <= 0
                  ? "NO_TIME_REMAINING"
                  : domainContext.isEmpty() ? "UNKNOWN_DOMAIN" : "NO_GROUNDABLE_EVIDENCE")
          .addKeyValue("h6DataStatus", assembled.h6DataStatus().name())
          .addKeyValue("allowedMisconceptions", context.allowedMisconceptionIds().size())
          .addKeyValue("allowedEvidenceRefs", context.allowedEvidenceRefs().size())
          .addKeyValue("interactionId", interactionId)
          .log("no groundable H6/H7 evidence; no model call, deterministic diagnostic path unchanged");
      // Route the no-op through the same seam so there is exactly one path to ABSENT and nothing
      // is written.
      return proposalService.evaluate(null, context, correlation);
    }

    AiRequestEnvelope request =
        new AiRequestEnvelope(
            AiRequestEnvelope.CONTRACT_VERSION,
            interactionId,
            requestId,
            new LearnerRef(learnerId.toString(), null),
            null,
            domainContext.get(),
            null,
            new Constraints(
                InteractionClass.INTERACTIVE_AI, (int) deadlineMillis, null, null, null),
            null);

    DelegatedAiExecutionContext delegated =
        delegatedContextMinter.mint(
            interactionId,
            learnerId.toString(),
            context::domain,
            AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES);

    AiProposalEnvelope envelope;
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(interactionId, MDC.get("traceId"))) {
      envelope = probePort.requestProbeRecommendation(request, deadlineMillis, delegated);
    } catch (AiUnavailableException unavailable) {
      LOGGER
          .atWarn()
          .addKeyValue("operation", "ai.diagnosticProbe.recommend")
          .addKeyValue("outcome", "ABSENT")
          .addKeyValue("reason", "AI_UNAVAILABLE")
          .addKeyValue("errorCode", unavailable.code())
          .addKeyValue("interactionId", interactionId)
          .log("diagnostic-probe AI unavailable; treated as ABSENT, deterministic path unchanged");
      envelope = null;
    }

    return proposalService.evaluate(envelope, context, correlation);
  }
}
