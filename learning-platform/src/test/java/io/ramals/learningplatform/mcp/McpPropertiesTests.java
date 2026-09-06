package io.ramals.learningplatform.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * M2-ADR-031: {@link McpProperties#getWorkloadAudience()} (the MCP transport's own workload
 * authentication, {@code aud=ramals-ai}) and {@link McpProperties.DelegatedContext#getAudience()}
 * (the delegated learner-context credential, {@code aud=ramals-mcp}) are two independent fields with
 * two independent defaults -- proving structurally, not just by convention, that they can never
 * collapse into one audience by accident. See {@link io.ramals.learningplatform.mcp.McpSecurityConfig}
 * for where a workload token's audience is enforced, and {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator} for where a delegated
 * context's own, different, audience is enforced.
 */
class McpPropertiesTests {

  @Test
  void defaultsToDisabledWithDistinctWorkloadAndDelegatedContextAudiences() {
    McpProperties properties = new McpProperties();

    assertThat(properties.isEnabled()).isFalse();
    assertThat(properties.getWorkloadAudience()).isEqualTo("ramals-ai");
    assertThat(properties.getDelegatedContext().getAudience()).isEqualTo("ramals-mcp");
    assertThat(properties.getWorkloadAudience())
        .isNotEqualTo(properties.getDelegatedContext().getAudience());
  }

  @Test
  void changingWorkloadAudienceLeavesDelegatedContextAudienceUntouched() {
    McpProperties properties = new McpProperties();

    properties.setWorkloadAudience("some-other-workload-audience");

    assertThat(properties.getDelegatedContext().getAudience()).isEqualTo("ramals-mcp");
  }

  @Test
  void changingDelegatedContextAudienceLeavesWorkloadAudienceUntouched() {
    McpProperties properties = new McpProperties();

    properties.getDelegatedContext().setAudience("some-other-delegated-audience");

    assertThat(properties.getWorkloadAudience()).isEqualTo("ramals-ai");
  }

  @Test
  void delegatedContextDefaultsRequireNoKeyMaterial() {
    McpProperties.DelegatedContext delegatedContext = new McpProperties().getDelegatedContext();

    assertThat(delegatedContext.getIssuer()).isEqualTo("ramals-learning-platform");
    assertThat(delegatedContext.getTtlSeconds()).isEqualTo(120);
    assertThat(delegatedContext.getActiveKeyId()).isEmpty();
    assertThat(delegatedContext.getKeys()).isEmpty();
  }

  @Test
  void delegatedContextToStringNeverIncludesKeyMaterial() {
    McpProperties.DelegatedContext delegatedContext = new McpProperties().getDelegatedContext();
    delegatedContext.setActiveKeyId("current");
    delegatedContext.setKeys(java.util.Map.of("current", "c2VjcmV0LW1hdGVyaWFsLW5vdC1yZWFsLTMyLWJ5dGVzIQ=="));

    String rendered = delegatedContext.toString();

    assertThat(rendered).contains("activeKeyId=current").contains("knownKeyIds=[current]");
    assertThat(rendered).doesNotContain("c2VjcmV0LW1hdGVyaWFsLW5vdC1yZWFsLTMyLWJ5dGVzIQ==");
  }
}
