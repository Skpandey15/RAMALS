package io.ramals.learningplatform.grounding;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Production wiring for deterministic grounding retrieval and validation. */
@Configuration(proxyBeanMethods = false)
public class GroundingConfiguration {

  @Bean
  GroundedContextValidator groundedContextValidator(ObjectMapper mapper) {
    return new GroundedContextValidator(mapper);
  }

  @Bean
  GroundedContextFactory groundedContextFactory(GroundedContextValidator validator) {
    return new GroundedContextFactory(validator);
  }

  @Bean
  GroundingRetrievalService groundingRetrievalService(
      GroundingRetrievalPort retrieval,
      GroundedContextFactory factory) {
    return new GroundingRetrievalService(
        retrieval, factory, GroundingRetrievalPolicy.V1, Clock.systemUTC());
  }

  @Bean
  ProposalGroundingGate proposalGroundingGate(GroundedContextValidator validator) {
    return new ProposalGroundingGate(validator, new ProposalGroundingPolicy());
  }

  @Bean
  io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentProposalGate
      diagnosticAssessmentProposalGate(ProposalGroundingGate grounding) {
    return new io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentProposalGate(
        grounding);
  }

  @Bean
  io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentService
      diagnosticAssessmentService(
          GroundingRetrievalService retrieval,
          io.ramals.learningplatform.ai.DiagnosticAssessmentPort agent,
          io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentProposalGate gate,
          ProposalGateDecisionPort decisions,
          io.ramals.learningplatform.execution.DiagnosticAssessmentExecutionRecorder executions) {
    return new io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentService(
        retrieval, agent, gate, decisions, executions, Clock.systemUTC());
  }

  @Bean
  ProposalGroundingService proposalGroundingService(
      ProposalGroundingGate gate,
      ProposalGateDecisionPort decisions) {
    return new ProposalGroundingService(gate, decisions, Clock.systemUTC());
  }
}
