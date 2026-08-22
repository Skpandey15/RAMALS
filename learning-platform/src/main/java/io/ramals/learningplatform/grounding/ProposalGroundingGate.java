package io.ramals.learningplatform.grounding;

import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Spring-owned fail-closed evidence gate. It performs no model calls and has no mutable state. */
public final class ProposalGroundingGate {
  private static final int MAX_CLAIMS = 64;

  private final GroundedContextValidator contextValidator;
  private final ProposalGroundingPolicy policy;

  public ProposalGroundingGate(
      GroundedContextValidator contextValidator,
      ProposalGroundingPolicy policy) {
    this.contextValidator = contextValidator;
    this.policy = policy;
  }

  public ProposalGateResult evaluate(
      ProposalGroundingRequest proposal,
      GroundedContext context,
      Instant now) {
    Set<ProposalGateReason> reasons = new HashSet<>();
    Set<String> referenced = new HashSet<>();
    if (!validEnvelope(proposal)) {
      reasons.add(ProposalGateReason.PROPOSAL_INVALID);
      return rejected(reasons, referenced);
    }
    if (!GroundedContext.CONTRACT_VERSION.equals(proposal.contractVersion())) {
      reasons.add(ProposalGateReason.PROPOSAL_VERSION_UNSUPPORTED);
    }
    if (context == null || !proposal.contextId().equals(context.contextId())) {
      reasons.add(ProposalGateReason.CONTEXT_ID_MISMATCH);
      return rejected(reasons, referenced);
    }
    try {
      contextValidator.validate(context, policy.requiredSources(proposal.proposalType()), now);
    } catch (GroundedContextValidator.GroundedContextException invalid) {
      reasons.add(ProposalGateReason.GROUNDING_INVALID);
      return rejected(reasons, referenced);
    }

    Map<String, GroundedContextItem> supplied = new HashMap<>();
    context.items().forEach(item -> supplied.put(item.evidenceId(), item));
    for (GroundedClaim claim : proposal.claims()) {
      if (claim == null || claim.claimId() == null || claim.claimId().isBlank()
          || claim.claimId().length() > 128 || claim.evidenceIds().isEmpty()) {
        reasons.add(ProposalGateReason.CLAIM_UNSUPPORTED);
        continue;
      }
      boolean hasPermittedAuthoritativeEvidence = false;
      for (String evidenceId : claim.evidenceIds()) {
        if (!bounded(evidenceId)) {
          reasons.add(ProposalGateReason.EVIDENCE_REFERENCE_UNKNOWN);
          continue;
        }
        referenced.add(evidenceId);
        GroundedContextItem item = supplied.get(evidenceId);
        if (item == null) {
          reasons.add(ProposalGateReason.EVIDENCE_REFERENCE_UNKNOWN);
        } else if (item.authority() != ContextAuthority.AUTHORITATIVE_FACT) {
          reasons.add(ProposalGateReason.EVIDENCE_REFERENCE_NON_AUTHORITATIVE);
        } else if (policy.claimEvidenceSources(proposal.proposalType()).contains(item.sourceType())) {
          hasPermittedAuthoritativeEvidence = true;
        }
      }
      if (!hasPermittedAuthoritativeEvidence) {
        reasons.add(ProposalGateReason.CLAIM_UNSUPPORTED);
      }
    }
    if (proposal.confidence().compareTo(policy.minimumConfidence(proposal.proposalType())) < 0) {
      reasons.add(ProposalGateReason.CONFIDENCE_BELOW_POLICY);
    }
    if (reasons.isEmpty()) {
      return new ProposalGateResult(true, List.of(ProposalGateReason.ACCEPTED), referenced);
    }
    return rejected(reasons, referenced);
  }

  private static boolean validEnvelope(ProposalGroundingRequest proposal) {
    return proposal != null
        && bounded(proposal.proposalId())
        && bounded(proposal.requestId())
        && bounded(proposal.agentRunId())
        && bounded(proposal.contextId())
        && proposal.proposalType() != null
        && proposal.confidence() != null
        && proposal.confidence().signum() >= 0
        && proposal.confidence().compareTo(java.math.BigDecimal.ONE) <= 0
        && !proposal.claims().isEmpty()
        && proposal.claims().size() <= MAX_CLAIMS;
  }

  private static boolean bounded(String value) {
    return value != null && !value.isBlank() && value.length() <= 64;
  }

  private static ProposalGateResult rejected(
      Set<ProposalGateReason> reasons,
      Set<String> referenced) {
    List<ProposalGateReason> ordered = new ArrayList<>(reasons);
    ordered.sort(Comparator.comparing(Enum::name));
    return new ProposalGateResult(false, ordered, referenced);
  }
}
