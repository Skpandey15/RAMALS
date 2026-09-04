package io.ramals.learningplatform.assessment;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Startup validation for adaptive form composition, for the same reason
 * {@link DiagnosticFormConfiguration} checks V1's: a nonsensical quota should refuse to start, not
 * surface later as learners receiving an empty or absurd packet.
 */
@Configuration
@EnableConfigurationProperties(AdaptiveDiagnosticFormProperties.class)
class AdaptiveDiagnosticFormConfiguration {

  private final AdaptiveDiagnosticFormProperties properties;

  AdaptiveDiagnosticFormConfiguration(AdaptiveDiagnosticFormProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void validate() {
    if (properties.getSingleChoiceTarget() < 0) {
      throw new IllegalStateException(
          "ramals.diagnostic.adaptive-form.single-choice-target must not be negative.");
    }
    if (properties.getFillBlankTarget() < 0) {
      throw new IllegalStateException(
          "ramals.diagnostic.adaptive-form.fill-blank-target must not be negative.");
    }
    if (properties.getSingleChoiceTarget() + properties.getFillBlankTarget() < 1) {
      throw new IllegalStateException(
          "ramals.diagnostic.adaptive-form must target at least one item.");
    }
  }
}
