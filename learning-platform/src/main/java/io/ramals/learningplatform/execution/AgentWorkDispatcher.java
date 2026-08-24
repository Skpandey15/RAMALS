package io.ramals.learningplatform.execution;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded polling dispatcher. Claims commit before any remote call begins. */
@Component
public class AgentWorkDispatcher {

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentWorkDispatcher.class);
  private final AgentWorkOutboxRepository repository;
  private final AgentWorkProcessor processor;
  private final AgentWorkDispatcherProperties properties;
  private final MeterRegistry metrics;
  private final String owner = UUID.randomUUID().toString();

  public AgentWorkDispatcher(AgentWorkOutboxRepository repository, AgentWorkProcessor processor,
      AgentWorkDispatcherProperties properties, MeterRegistry metrics) {
    this.repository = repository;
    this.processor = processor;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${ramals.ai.dispatcher.poll-interval-ms:1000}")
  public void poll() {
    if (!properties.isEnabled()) return;
    try {
      for (ClaimedAgentWork work : repository.claim(
          owner, properties.getBatchSize(), properties.getLeaseMillis())) {
        dispatch(work);
      }
    } catch (RuntimeException failure) {
      metrics.counter("ramals.ai.dispatch.poll", "outcome", "failed").increment();
      LOGGER.atWarn().addKeyValue("operation", "ai.work.poll")
          .addKeyValue("outcome", "FAILED")
          .addKeyValue("errorType", failure.getClass().getSimpleName())
          .log("agent work poll failed; the scheduler will retry", failure);
    }
  }

  void dispatch(ClaimedAgentWork work) {
    long started = System.nanoTime();
    try {
      processor.process(work);
      repository.complete(work);
      record("completed", work, started, null);
    } catch (RuntimeException failure) {
      String code = failure instanceof io.ramals.learningplatform.ai.AiUnavailableException ai
          ? ai.code() : "AGENT_WORK_UNEXPECTED";
      if (work.attemptCount() >= properties.getMaxAttempts() || !transientFailure(failure)) {
        processor.recordTerminalFailure(work, code);
        repository.terminal(work, code);
        record("terminal", work, started, code);
      } else {
        repository.retry(work, code, backoffMillis(work));
        record("retry", work, started, code);
      }
    }
  }

  private long backoffMillis(ClaimedAgentWork work) {
    int exponent = Math.min(20, Math.max(0, work.attemptCount() - 1));
    long base = Math.min(properties.getMaxBackoffMillis(),
        properties.getInitialBackoffMillis() * (1L << exponent));
    long spread = Math.max(1, base / 5);
    long deterministicJitter = Math.floorMod(work.requestId().hashCode(), spread * 2 + 1) - spread;
    return Math.max(1, base + deterministicJitter);
  }

  private static boolean transientFailure(RuntimeException failure) {
    if (failure instanceof io.ramals.learningplatform.ai.AiUnavailableException ai) {
      return ai.origin() != io.ramals.learningplatform.ai.FailureOrigin.CALLER;
    }
    return true;
  }

  private void record(String outcome, ClaimedAgentWork work, long started, String errorCode) {
    metrics.counter("ramals.ai.dispatch", "outcome", outcome).increment();
    metrics.timer("ramals.ai.dispatch.duration", "outcome", outcome)
        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(work.interactionId(), work.traceId())) {
      var event = "completed".equals(outcome) ? LOGGER.atInfo() : LOGGER.atWarn();
      event.addKeyValue("operation", "ai.work.dispatch")
          .addKeyValue("outcome", outcome.toUpperCase(Locale.ROOT))
          .addKeyValue("workId", work.id()).addKeyValue("requestId", work.requestId())
          .addKeyValue("attempt", work.attemptCount()).addKeyValue("errorCode", errorCode)
          .log("agent work dispatch finished");
    }
  }
}
