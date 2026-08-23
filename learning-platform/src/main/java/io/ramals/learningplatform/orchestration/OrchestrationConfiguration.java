package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.mastery.MasteryService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the deterministic composition. The clock is injected so timeouts are testable. */
@Configuration
public class OrchestrationConfiguration {

  @Bean
  LearningWorkflowOrchestrator learningWorkflowOrchestrator(
      LearningWorkflowRepository runs,
      EvidenceService evidence,
      MasteryService mastery,
      WorkflowAgentStep.Diagnostic diagnostic,
      WorkflowAgentStep.Adaptation adaptation) {
    return new LearningWorkflowOrchestrator(
        runs, evidence, mastery, diagnostic, adaptation, Clock.systemUTC());
  }
}
