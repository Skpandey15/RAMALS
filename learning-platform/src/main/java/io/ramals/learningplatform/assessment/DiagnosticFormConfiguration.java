package io.ramals.learningplatform.assessment;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Startup validation for form selection.
 *
 * <p>Checked here rather than at first use, where a nonsensical value would surface as learners
 * receiving empty or absurd diagnostics -- with the evidence already written by the time anybody
 * noticed. As a startup condition, a misconfigured deployment never becomes reachable.
 */
@Configuration
@EnableConfigurationProperties(DiagnosticFormProperties.class)
class DiagnosticFormConfiguration {

  private final DiagnosticFormProperties properties;

  DiagnosticFormConfiguration(DiagnosticFormProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void validate() {
    if (properties.getTargetSize() < 1) {
      throw new IllegalStateException(
          "ramals.diagnostic.form.target-size must be at least 1 item.");
    }
    if (properties.getRecencyWindowDays() < 0) {
      throw new IllegalStateException(
          "ramals.diagnostic.form.recency-window-days must not be negative.");
    }
  }
}
