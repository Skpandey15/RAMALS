package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContext;
import org.junit.jupiter.api.Test;

/** MCP-3.1: least-privilege capability allowlists are exact, minimal, and never a wildcard. */
class AiDelegatedCapabilityPolicyTests {

  @Test
  void diagnosticAssessmentAllowlistIsExactlyTheFourH6H7Reads() {
    assertThat(AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES).containsExactlyInAnyOrder(
        "diagnostics.current-domain-report",
        "diagnostics.attempt-report",
        "diagnostics.longitudinal-summary",
        "diagnostics.misconception-longitudinal-detail");
    assertThat(AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES)
        .doesNotContain(AiDelegatedCapabilityPolicy.MASTERY_CURRENT);
  }

  @Test
  void adaptationAllowlistIsExactlyMasteryPlusTwoDiagnosticReads() {
    assertThat(AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES).containsExactlyInAnyOrder(
        "mastery.current",
        "diagnostics.current-domain-report",
        "diagnostics.longitudinal-summary");
    assertThat(AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES)
        .doesNotContain(
            AiDelegatedCapabilityPolicy.ATTEMPT_REPORT,
            AiDelegatedCapabilityPolicy.MISCONCEPTION_LONGITUDINAL_DETAIL);
  }

  @Test
  void neitherAllowlistContainsAWildcard() {
    assertThat(AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES)
        .noneMatch(capability -> capability.contains("*"));
    assertThat(AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES)
        .noneMatch(capability -> capability.contains("*"));
  }

  @Test
  void everyCapabilityNameMatchesTheGovernedShapeTheRealTokenRecordEnforces() {
    // Reuses the production pattern directly, rather than a second copy of it: a name this policy
    // could ever mint must already be one DelegatedLearnerContext's own compact constructor accepts.
    AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES.forEach(capability ->
        assertThat(DelegatedLearnerContext.CAPABILITY_NAME_PATTERN.matcher(capability).matches())
            .isTrue());
    AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES.forEach(capability ->
        assertThat(DelegatedLearnerContext.CAPABILITY_NAME_PATTERN.matcher(capability).matches())
            .isTrue());
  }

  @Test
  void theTwoAllowlistsAreDistinctSetsNeitherIsTheOtherWidened() {
    assertThat(AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES)
        .isNotEqualTo(AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES);
  }
}
