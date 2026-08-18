package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdaptationServiceTests {

  @Test
  void deterministicDecisionWinsWhenAiDisagrees() {
    AdaptationService service = new AdaptationService(
        (request, deadline) -> proposal("ADVANCE"), new AdaptationProposalGate(new SimpleMeterRegistry()));

    AdaptationService.Outcome outcome = service.compare(
        request(), new RecommendationDecision(RecommendedAction.PRACTICE, "POLICY"), 8_000);

    assertThat(outcome.deterministicDecision().action()).isEqualTo(RecommendedAction.PRACTICE);
    assertThat(outcome.disagreement()).isTrue();
  }

  @Test
  void aiFailureLeavesDeterministicDecisionAvailable() {
    AdaptationService service = new AdaptationService(
        (request, deadline) -> { throw new AiUnavailableException("AI_TIMEOUT", "timed out"); },
        new AdaptationProposalGate(new SimpleMeterRegistry()));

    AdaptationService.Outcome outcome = service.compare(
        request(), new RecommendationDecision(RecommendedAction.RETEACH, "POLICY"), 8_000);

    assertThat(outcome.deterministicDecision().action()).isEqualTo(RecommendedAction.RETEACH);
    assertThat(outcome.proposal()).isNull();
    assertThat(outcome.disagreement()).isFalse();
  }

  @Test
  void adaptationProposalIsNonAuthoritative() {
    AiProposalEnvelope proposal = proposal("PRACTICE");
    assertThat(proposal.trustLevel()).isEqualTo(TrustLevel.NON_AUTHORITATIVE);
    assertThat(proposal.agentType()).isEqualTo(AgentType.ADAPTATION);
  }

  private static AiProposalEnvelope proposal(String action) {
    return new AiProposalEnvelope(
        "1.0", "proposal-1", AgentType.ADAPTATION, "adaptation-agent-v1", "prompt-v1",
        "adaptation-default", TrustLevel.NON_AUTHORITATIVE, null, null,
        Map.of("skillCode", "SKILL-1", "recommendedAction", action, "rationale", "reason"), null, null);
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope(
        "1.0", "interaction-1", "request-1", null,
        new LearningContext("SKILL-1", null, null, "DEVELOPING", null),
        new DomainContext("DOMAIN", DomainType.TECHNOLOGY, "v1"), null,
        new Constraints(InteractionClass.INTERACTIVE_AI, 8_000, null, null, null), "NEXT_ACTION");
  }
}
