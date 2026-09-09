package io.ramals.learningplatform.diagnosticassessment;

import java.util.List;
import java.util.UUID;

/**
 * The immutable audit record for one advisory diagnostic-probe proposal decision (M2-ADR-032 19).
 *
 * <p>Carries exactly what an incident needs to answer "which interaction produced it; which
 * prompt/provider/route; which governed evidence was supplied ({@code allowedEvidenceRefs}) and
 * which the AI cited ({@code citedEvidenceRefs}); which misconception/target; which schema and gate
 * policy version; accepted or rejected, and why". It carries no prompt text and no model
 * chain-of-thought (M2-ADR-005, unchanged).
 *
 * <p>{@code parserReasonCode} is set only when the payload could not be read as the v1 contract at
 * all; the target/intent fields are then {@code null} because nothing could be parsed.
 */
public record DiagnosticProbeProposalDecision(
    String proposalId,
    String requestId,
    String agentRunId,
    String interactionId,
    String traceId,
    UUID learnerId,
    String domainCode,
    String proposalContractVersion,
    String policyVersion,
    boolean accepted,
    List<String> reasonCodes,
    String parserReasonCode,
    UUID targetMisconceptionId,
    String targetNodeKind,
    UUID targetNodeId,
    String probeIntent,
    UUID candidateProbeRef,
    List<String> allowedEvidenceRefs,
    List<String> citedEvidenceRefs,
    String promptTemplateId,
    String promptVersion,
    String modelRoute,
    String resolvedProvider,
    String modelId,
    String routeVersion) {

  public DiagnosticProbeProposalDecision {
    reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    allowedEvidenceRefs = allowedEvidenceRefs == null ? List.of() : List.copyOf(allowedEvidenceRefs);
    citedEvidenceRefs = citedEvidenceRefs == null ? List.of() : List.copyOf(citedEvidenceRefs);
  }
}
