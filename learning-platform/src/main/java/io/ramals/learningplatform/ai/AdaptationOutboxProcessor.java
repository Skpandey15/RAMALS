package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.execution.AgentWorkProcessor;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionRecoveryPort;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import io.ramals.learningplatform.execution.ClaimedAgentWork;
import io.ramals.learningplatform.qualification.QualificationFault;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Adaptation handler for durable outbox work; it never runs inside the claim transaction. */
@Component
public class AdaptationOutboxProcessor implements AgentWorkProcessor {

  static final long DEADLINE_MS = 12_000;
  private final AdaptationService adaptation;
  private final AiExecutionRecorder executions;
  private final AiExecutionRecoveryPort recovery;

  public AdaptationOutboxProcessor(
      AdaptationService adaptation,
      AiExecutionRecorder executions,
      AiExecutionRecoveryPort recovery) {
    this.adaptation = adaptation;
    this.executions = executions;
    this.recovery = recovery;
  }

  @Override
  public void process(ClaimedAgentWork work) {
    if (!"ADAPTATION".equals(work.agentType()) || !"ADAPT".equals(work.capability())) {
      throw new IllegalArgumentException("UNSUPPORTED_AGENT_WORK");
    }
    AiRequestEnvelope request = envelope(work);
    try (var interaction = MDC.putCloseable("interactionId", work.interactionId());
         var trace = MDC.putCloseable("traceId", work.traceId())) {
      AiExecutionCommission commission = executions.commission(request, "ADAPTATION");
      if (!commission.dispatchAllowed()) {
        recoverWithoutDispatch(request, commission);
        return;
      }

      // Commission is the durable at-most-once boundary. A crash after this point must never
      // reach the provider again on an outbox replay, even if the first call's outcome is unknown.
      QualificationFault.pause(
          QualificationFault.Window.ADAPTATION_AFTER_COMMISSION, null, request.requestId());

      Instant started = Instant.now();
      try {
        var outcome = adaptation.compareRequired(
            request,
            new RecommendationDecision(work.recommendedAction(), work.reasonCode()),
            DEADLINE_MS);
        executions.recordSuccess(request, outcome.proposal(), started, Instant.now());
      } catch (RuntimeException failure) {
        // A provider failure must settle the commissioned execution before the dispatcher decides
        // whether the outbox item is retryable. Otherwise the retry sees STARTED and can never make
        // progress without violating the at-most-once dispatch rule.
        String errorCode = failure instanceof AiUnavailableException ai
            ? ai.code() : "AI_EXECUTION_FAILED";
        executions.recordFailure(request, "ADAPTATION", errorCode, started, Instant.now());
        throw failure;
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
    } else if (commission.existingExecution().isEmpty()) {
      recovery.closeAbandonedExecution(request.requestId(), errorCode);
    } else if (!"SUCCEEDED".equals(commission.existingExecution().orElseThrow().status())
        && !"FAILED".equals(commission.existingExecution().orElseThrow().status())) {
      recovery.closeAbandonedExecution(request.requestId(), errorCode);
    }
  }

  /** A commissioned replay is terminally accounted for, but it is never redispatched. */
  private void recoverWithoutDispatch(
      AiRequestEnvelope request, AiExecutionCommission commission) {
    if (commission.existingExecution().isPresent()) {
      String status = commission.existingExecution().orElseThrow().status();
      if ("SUCCEEDED".equals(status)) {
        return;
      }
      throw new AiUnavailableException(
          "AI_EXECUTION_ALREADY_FAILED",
          "The adaptation execution already has a terminal failure.",
          FailureOrigin.CALLER);
    }

    recovery.closeAbandonedExecution(request.requestId(), "AI_EXECUTION_ABANDONED");
    var state = recovery.findExecutionState(request.requestId());
    if (state.state() == AiExecutionRecoveryPort.ExecutionState.SUCCEEDED) {
      return;
    }
    throw new AiUnavailableException(
        "AI_EXECUTION_ABANDONED",
        "A commissioned adaptation execution had no durable outcome; it was not redispatched.",
        FailureOrigin.CALLER);
  }

  private static AiRequestEnvelope envelope(ClaimedAgentWork work) {
    return new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION, work.interactionId(), work.requestId(),
        new LearnerRef(work.learnerId().toString(), Locale.ENGLISH.toLanguageTag()),
        new LearningContext(work.skillId().toString(), null, null, null, null), null, null,
        new Constraints(InteractionClass.INTERACTIVE_AI, (int) DEADLINE_MS, null, null, null),
        "ADAPT");
  }
}
