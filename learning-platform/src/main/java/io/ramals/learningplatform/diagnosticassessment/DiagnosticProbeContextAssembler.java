package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.AiDelegatedCapabilityPolicy;
import io.ramals.learningplatform.assessment.DiagnosticReport;
import io.ramals.learningplatform.assessment.DiagnosticReportService;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceService;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Builds the authoritative {@link DiagnosticProbeProposalContext} for one interaction, from Java's
 * own governed H6/H7 read services only (M2-ADR-032 step 3, §4).
 *
 * <p><b>Nothing here is ever taken from an AI payload.</b> The learner, interaction and domain are
 * the interaction's own; {@code M_allowed} and {@code E_allowed} are read independently from the
 * exact services the MCP-2 tools ({@code diagnostics.current-domain-report},
 * {@code diagnostics.longitudinal-summary}) already expose to the AI plane, so the set the gate
 * enforces against is Java's, computed the same way the model's own read resolves it.
 *
 * <ul>
 *   <li>{@code M_allowed} -- every misconception id surfaced by H6 ({@link
 *       DiagnosticReportService#currentDomainReportForLearner}) or H7 ({@link
 *       LongitudinalEvidenceService#domainSummaryForLearner}) for this learner and domain.
 *   <li>{@code E_allowed} -- every post-baseline evidence-observation id H7 exposes
 *       ({@code laterEvidenceObservationIds}); this is the only governed evidence-id set a
 *       learner-scoped H6/H7 read makes citable, so it is the exact, deterministic evidence snapshot
 *       the gate binds a citation to. It is persisted verbatim on every decision row by
 *       {@link DiagnosticProbeProposalService} (M2-ADR-032 19), which is the recorded watermark.
 *   <li>{@code allowedCandidateProbeRefs} -- <b>empty</b> in step 3: the AI may name a bounded
 *       {@code probeIntent}, never a specific probe object, so any concrete {@code candidateProbeRef}
 *       it returns is rejected by the existing gate (M2-ADR-032 13).
 *   <li>{@code delegatedCapabilities} -- the H6/H7 read capabilities this diagnostic interaction is
 *       authorized for ({@link AiDelegatedCapabilityPolicy#DIAGNOSTIC_ASSESSMENT_CAPABILITIES}).
 * </ul>
 *
 * <p>Read-only: it calls two {@code @Transactional(readOnly = true)} services and writes nothing.
 */
public class DiagnosticProbeContextAssembler {

  private final DiagnosticReportService diagnosticReportService;
  private final LongitudinalEvidenceService longitudinalEvidenceService;

  public DiagnosticProbeContextAssembler(
      DiagnosticReportService diagnosticReportService,
      LongitudinalEvidenceService longitudinalEvidenceService) {
    this.diagnosticReportService = diagnosticReportService;
    this.longitudinalEvidenceService = longitudinalEvidenceService;
  }

  /**
   * Assembles the context for {@code (learnerId, domainCode, interactionId)}.
   *
   * @return the authoritative context, plus {@link Assembled#groundable()} -- {@code false} when H6
   *     reports {@code NO_EVIDENCE}, or {@code M_allowed}/{@code E_allowed} is empty, i.e. there is
   *     nothing for the AI to reason a probe recommendation from. A caller must not invoke the model
   *     when {@code groundable()} is {@code false} (M2-ADR-032 15).
   */
  public Assembled assemble(UUID learnerId, String domainCode, String interactionId) {
    String normalizedDomain = domainCode == null ? null : domainCode.toUpperCase(Locale.ROOT);

    DiagnosticReport h6 =
        diagnosticReportService.currentDomainReportForLearner(learnerId, normalizedDomain);
    LongitudinalEvidenceReport h7 =
        longitudinalEvidenceService.domainSummaryForLearner(learnerId, normalizedDomain);

    Set<UUID> allowedMisconceptionIds = new LinkedHashSet<>();
    h6.misconceptionFindings().forEach(finding -> allowedMisconceptionIds.add(finding.misconceptionId()));
    h7.findings().forEach(finding -> allowedMisconceptionIds.add(finding.misconceptionId()));

    Set<String> allowedEvidenceRefs = new TreeSet<>();
    h7.findings()
        .forEach(
            finding ->
                finding
                    .laterEvidenceObservationIds()
                    .forEach(id -> allowedEvidenceRefs.add(id.toString())));

    DiagnosticProbeProposalContext context =
        new DiagnosticProbeProposalContext(
            learnerId,
            interactionId,
            normalizedDomain,
            allowedMisconceptionIds,
            allowedEvidenceRefs,
            Set.of(),
            AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES);

    boolean groundable =
        context.isComplete()
            && h6.diagnosticDataStatus() == DiagnosticReport.DiagnosticDataStatus.HAS_EVIDENCE
            && !allowedMisconceptionIds.isEmpty()
            && !allowedEvidenceRefs.isEmpty();

    return new Assembled(context, groundable, h6.diagnosticDataStatus());
  }

  /**
   * The assembled authoritative context and whether the model may be invoked for it.
   *
   * @param context the authoritative {@link DiagnosticProbeProposalContext}; the gate's sole source
   *     of truth for this interaction
   * @param groundable {@code false} when there is nothing to reason from -- the caller returns
   *     {@code ABSENT} without a model call
   * @param h6DataStatus H6's own {@code NO_EVIDENCE}/{@code HAS_EVIDENCE}, kept for the log line
   */
  public record Assembled(
      DiagnosticProbeProposalContext context,
      boolean groundable,
      DiagnosticReport.DiagnosticDataStatus h6DataStatus) {}
}
