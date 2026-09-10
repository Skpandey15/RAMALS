package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.DelegatedAiContextMinter;
import io.ramals.learningplatform.ai.DiagnosticProbePort;
import io.ramals.learningplatform.ai.DomainContextAssembler;
import io.ramals.learningplatform.assessment.DiagnosticReportService;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the advisory diagnostic-probe proposal gate and its evaluation seam (M2-ADR-032 step
 * 2).
 *
 * <p>Step 2 built the versioned contract and the deterministic gate. Step 3 (M2-ADR-032 §21) adds
 * the reasoning call site: {@link DiagnosticProbeContextAssembler} builds the authoritative context
 * from Java's own H6/H7 read services, and {@link DiagnosticProbeRecommendationOrchestrator} solicits
 * one bounded AI recommendation and routes it through the unchanged {@link
 * DiagnosticProbeProposalService}/{@link DiagnosticProbeProposalGate}. No accepted recommendation is
 * consumed or executed in step 3.
 *
 * <p>The two JDBC adapters ({@link JdbcDiagnosticProbeTargetRepository},
 * {@link JdbcDiagnosticProbeProposalDecisionRepository}) are {@code @Repository}-annotated and
 * component-scanned.
 */
@Configuration(proxyBeanMethods = false)
public class DiagnosticProbeProposalConfiguration {

  @Bean
  DiagnosticProbeProposalGate diagnosticProbeProposalGate() {
    return new DiagnosticProbeProposalGate();
  }

  @Bean
  DiagnosticProbeProposalService diagnosticProbeProposalService(
      DiagnosticProbeProposalGate gate,
      DiagnosticProbeTargetPort targetPort,
      DiagnosticProbeProposalDecisionPort decisions) {
    return new DiagnosticProbeProposalService(gate, targetPort, decisions);
  }

  @Bean
  DiagnosticProbeContextAssembler diagnosticProbeContextAssembler(
      DiagnosticReportService diagnosticReportService,
      LongitudinalEvidenceService longitudinalEvidenceService) {
    return new DiagnosticProbeContextAssembler(diagnosticReportService, longitudinalEvidenceService);
  }

  @Bean
  DiagnosticProbeRecommendationOrchestrator diagnosticProbeRecommendationOrchestrator(
      DiagnosticProbeContextAssembler assembler,
      DomainContextAssembler domainContextAssembler,
      DiagnosticProbePort probePort,
      DiagnosticProbeProposalService proposalService,
      DelegatedAiContextMinter delegatedContextMinter) {
    return new DiagnosticProbeRecommendationOrchestrator(
        assembler, domainContextAssembler, probePort, proposalService, delegatedContextMinter);
  }
}
