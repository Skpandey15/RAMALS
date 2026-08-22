package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.execution.AgentWorkProcessor;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import io.ramals.learningplatform.execution.ClaimedAgentWork;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Adaptation handler for durable outbox work; it never runs inside the claim transaction. */
@Component
public class AdaptationOutboxProcessor implements AgentWorkProcessor {

  static final long DEADLINE_MS = 12_000;
  private final AdaptationService adaptation;
  private final AiExecutionRecorder executions;

  public AdaptationOutboxProcessor(AdaptationService adaptation, AiExecutionRecorder executions) {
    this.adaptation = adaptation;
    this.executions = executions;
  }

  @Override
  public void process(ClaimedAgentWork work) {
    if (!"ADAPTATION".equals(work.agentType()) || !"ADAPT".equals(work.capability())) {
      throw new IllegalArgumentException("UNSUPPORTED_AGENT_WORK");
    }
    AiRequestEnvelope request = envelope(work);
    Instant started = Instant.now();
    try (var interaction = MDC.putCloseable("interactionId", work.interactionId());
         var trace = MDC.putCloseable("traceId", work.traceId())) {
      var outcome = adaptation.compareRequired(request,
          new RecommendationDecision(work.recommendedAction(), work.reasonCode()), DEADLINE_MS);
      AiExecutionCommission commission = executions.commission(request, "ADAPTATION");
      if (commission.dispatchAllowed()) {
        executions.recordSuccess(request, outcome.proposal(), started, Instant.now());
      } else if (commission.existingExecution().isEmpty()
          || !"SUCCEEDED".equals(commission.existingExecution().orElseThrow().status())) {
        throw new AiUnavailableException(
            "AI_EXECUTION_IN_PROGRESS", "Execution accounting is not terminal.", FailureOrigin.GUARD);
      }
    }
  }

  @Override
  public void recordTerminalFailure(ClaimedAgentWork work, String errorCode) {
    AiRequestEnvelope request = envelope(work);
    AiExecutionCommission commission = executions.commission(request, "ADAPTATION");
    if (commission.dispatchAllowed()) {
      Instant now = Instant.now();
      executions.recordFailure(request, "ADAPTATION", errorCode, now, now);
    }
  }

  private static AiRequestEnvelope envelope(ClaimedAgentWork work) {
    return new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION, work.interactionId(), work.requestId(),
        new LearnerRef(UUID.randomUUID().toString(), Locale.ENGLISH.toLanguageTag()),
        new LearningContext(work.skillId().toString(), null, null, null, null), null, null,
        new Constraints(InteractionClass.INTERACTIVE_AI, (int) DEADLINE_MS, null, null, null),
        "ADAPT");
  }
}
