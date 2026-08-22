package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import org.springframework.stereotype.Service;

/** Compares an adaptation proposal with a precomputed deterministic decision. */
@Service
public class AdaptationService {

  public record Outcome(RecommendationDecision deterministicDecision,
                        AiProposalEnvelope proposal,
                        boolean disagreement) {}

  private final AdaptationPort adaptationPort;
  private final AdaptationProposalGate gate;

  public AdaptationService(AdaptationPort adaptationPort, AdaptationProposalGate gate) {
    this.adaptationPort = adaptationPort;
    this.gate = gate;
  }

  /** AI failures produce no proposal; the deterministic decision is always returned. */
  public Outcome compare(
      AiRequestEnvelope request,
      RecommendationDecision deterministicDecision,
      long deadlineMillis) {
    try {
      return compareRequired(request, deterministicDecision, deadlineMillis);
    } catch (AiUnavailableException failure) {
      return new Outcome(deterministicDecision, null, false);
    }
  }

  /** Same comparison, but preserves failures so a durable dispatcher can apply retry policy. */
  public Outcome compareRequired(
      AiRequestEnvelope request,
      RecommendationDecision deterministicDecision,
      long deadlineMillis) {
    AiProposalEnvelope proposal =
        adaptationPort.requestAdaptationProposal(request, deadlineMillis);
    AdaptationProposalGate.Result compared = gate.compare(
        deterministicDecision,
        new AdaptationProposalGate.Proposal(
            request.learningContext() == null ? null : request.learningContext().skillCode(),
            actionFrom(proposal)));
    return new Outcome(compared.deterministicDecision(), proposal, compared.disagreement());
  }

  private static String actionFrom(AiProposalEnvelope proposal) {
    if (proposal.proposal() == null) {
      return null;
    }
    Object action = proposal.proposal().get("recommendedAction");
    return action instanceof String value ? value : null;
  }
}
