package io.ramals.learningplatform.orchestration;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives running workflows forward, one step per pass.
 *
 * <p>Polling rather than in-line continuation is what makes the composition survive a restart: the
 * next step is decided from durable state, so a process that dies between two steps loses nothing
 * but time. It is also why no agent can chain into another -- an agent result ends a step, and only
 * this loop, reading the run row, decides whether a further step happens.
 */
@Component
public class LearningWorkflowAdvancer {

  private static final Logger LOGGER = LoggerFactory.getLogger(LearningWorkflowAdvancer.class);

  private final LearningWorkflowOrchestrator orchestrator;
  private final LearningWorkflowRepository runs;
  private final MeterRegistry metrics;
  private final boolean enabled;
  private final int batchSize;

  public LearningWorkflowAdvancer(
      LearningWorkflowOrchestrator orchestrator,
      LearningWorkflowRepository runs,
      MeterRegistry metrics,
      @Value("${ramals.orchestration.enabled:true}") boolean enabled,
      @Value("${ramals.orchestration.batch-size:16}") int batchSize) {
    this.orchestrator = orchestrator;
    this.runs = runs;
    this.metrics = metrics;
    this.enabled = enabled;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${ramals.orchestration.poll-interval-ms:1000}")
  public void poll() {
    if (!enabled) {
      return;
    }
    try {
      orchestrator.sweepTimeouts(batchSize);
      for (Run run : runs.running(batchSize)) {
        advance(run);
      }
    } catch (RuntimeException failure) {
      // One bad pass must not stop the scheduler; the next poll re-reads durable state.
      metrics.counter("ramals.workflow.poll", "outcome", "failed").increment();
      LOGGER
          .atWarn()
          .addKeyValue("operation", "workflow.poll")
          .addKeyValue("outcome", "FAILED")
          .addKeyValue("errorType", failure.getClass().getSimpleName())
          .log("workflow poll failed; the scheduler will retry", failure);
    }
  }

  private void advance(Run run) {
    try {
      Run advanced = orchestrator.advance(run.id());
      metrics
          .counter("ramals.workflow.advance", "status", advanced.status().name())
          .increment();
    } catch (RuntimeException failure) {
      // Isolated per run so one poisoned workflow cannot stall every other learner's.
      metrics.counter("ramals.workflow.advance", "status", "ERROR").increment();
      LOGGER
          .atWarn()
          .addKeyValue("operation", "workflow.advance")
          .addKeyValue("outcome", "FAILED")
          .addKeyValue("runId", run.id())
          .addKeyValue("errorType", failure.getClass().getSimpleName())
          .log("workflow advance failed", failure);
    }
  }
}
