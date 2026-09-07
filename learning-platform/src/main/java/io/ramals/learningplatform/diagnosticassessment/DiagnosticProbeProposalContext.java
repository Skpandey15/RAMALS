package io.ramals.learningplatform.diagnosticassessment;

import java.util.Set;
import java.util.UUID;

/**
 * The authoritative context an advisory diagnostic-probe proposal is judged against (M2-ADR-032 4).
 *
 * <p>Every value here is established by Java from the interaction it already authorized -- the
 * verified M2-ADR-031 delegated learner context, the interaction's own domain, and the exact
 * governed identifiers the interaction's MCP reads actually returned. <b>Nothing here is ever read
 * from the proposal payload.</b> A proposal can echo an interaction id or a domain for cross-check,
 * but it can never select or widen any of them (M2-ADR-032 4.2/4.3/4.4/12/21).
 *
 * @param authoritativeLearnerId the interaction's own learner, derived solely from the verified
 *     delegated context; the proposal carries no learner field and could not override this if it did
 * @param interactionId the interaction that produced the proposal
 * @param domain the interaction's authorized domain
 * @param allowedMisconceptionIds {@code M_allowed} -- the governed, authored misconception ids
 *     actually exposed to this interaction via its H6/H7 MCP reads (M2-ADR-032 12)
 * @param allowedEvidenceRefs {@code E_allowed} -- the exact governed evidence/provenance references
 *     supplied to this interaction; a citation outside this set is rejected outright (M2-ADR-032 11)
 * @param allowedCandidateProbeRefs authored probe/assessment object references the interaction is
 *     permitted to name a candidate from; empty means "the agent may name no specific probe object"
 * @param delegatedCapabilities the MCP capability names delegated to this interaction (M2-ADR-031);
 *     the recommendation may not imply a capability outside this allowlist
 */
public record DiagnosticProbeProposalContext(
    UUID authoritativeLearnerId,
    String interactionId,
    String domain,
    Set<UUID> allowedMisconceptionIds,
    Set<String> allowedEvidenceRefs,
    Set<UUID> allowedCandidateProbeRefs,
    Set<String> delegatedCapabilities) {

  public DiagnosticProbeProposalContext {
    allowedMisconceptionIds = allowedMisconceptionIds == null ? Set.of() : Set.copyOf(allowedMisconceptionIds);
    allowedEvidenceRefs = allowedEvidenceRefs == null ? Set.of() : Set.copyOf(allowedEvidenceRefs);
    allowedCandidateProbeRefs =
        allowedCandidateProbeRefs == null ? Set.of() : Set.copyOf(allowedCandidateProbeRefs);
    delegatedCapabilities = delegatedCapabilities == null ? Set.of() : Set.copyOf(delegatedCapabilities);
  }

  /**
   * True when this context carries enough authoritative binding to judge a proposal at all. An
   * absent learner, interaction or domain is a {@link DiagnosticProbeProposalGateReason#VALIDATION_UNAVAILABLE}
   * fail-closed rejection, never a best-effort acceptance (M2-ADR-032 14).
   */
  public boolean isComplete() {
    return authoritativeLearnerId != null
        && interactionId != null
        && !interactionId.isBlank()
        && domain != null
        && !domain.isBlank();
  }
}
