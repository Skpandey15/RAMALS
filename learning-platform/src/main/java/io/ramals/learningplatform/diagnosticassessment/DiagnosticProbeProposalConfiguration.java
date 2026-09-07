package io.ramals.learningplatform.diagnosticassessment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the advisory diagnostic-probe proposal gate and its evaluation seam (M2-ADR-032 step
 * 2).
 *
 * <p>No reasoning agent is wired here and no endpoint is exposed: this PR builds the versioned
 * contract and the deterministic Java gate so both are buildable and testable against synthetic
 * proposals before any model produces a real one (M2-ADR-032 23, step 2). The Python reasoning
 * implementation and its call site are a separate, later, separately reviewed PR (step 3).
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
}
