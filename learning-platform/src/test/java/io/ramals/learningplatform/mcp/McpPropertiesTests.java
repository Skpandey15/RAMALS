package io.ramals.learningplatform.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * M2-ADR-031: {@link McpProperties#getWorkloadAudience()} (a Keycloak-issued workload token
 * authenticating {@code ramals-ai} calling Java) and {@link
 * McpProperties.DelegatedContext#getAudience()} (a Java-self-issued delegated learner-context
 * credential) both default to the literal {@code ramals-mcp} -- both legitimately name "the receiver
 * is Java's MCP transport" -- but are two structurally independent fields on two independent
 * mechanisms, never one collapsed into the other: changing one never touches the other, and {@link
 * McpProperties#getWorkloadClientId()} (default {@code ramals-ai-workload}) additionally pins the
 * workload token to one specific Keycloak client, which the delegated-context credential has no
 * equivalent of at all (it is verified by signature/issuer, never by a client-identity claim). See
 * {@link io.ramals.learningplatform.mcp.McpSecurityConfig} for where a workload token's audience
 * <em>and</em> authorized-party are independently enforced, and {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator} for where a delegated
 * context's own audience is enforced by an entirely different (HS256/HMAC, not Keycloak/JWKS)
 * mechanism.
 */
class McpPropertiesTests {

  @Test
  void defaultsToDisabledWithWorkloadIdentityDistinctFromRamalsCoreWorkload() {
    McpProperties properties = new McpProperties();

    assertThat(properties.isEnabled()).isFalse();
    // Both name "receiver is Java's MCP transport" -- legitimately the same literal (M2-ADR-031
    // review) -- but never M1-ADR-003's ramals-ai (that names ramals-ai as receiver, the opposite
    // direction) and never ramals-api (the learner-facing API's own audience).
    assertThat(properties.getWorkloadAudience()).isEqualTo("ramals-mcp");
    assertThat(properties.getDelegatedContext().getAudience()).isEqualTo("ramals-mcp");
    assertThat(properties.getWorkloadAudience()).isNotEqualTo("ramals-ai");
    assertThat(properties.getWorkloadAudience()).isNotEqualTo("ramals-api");
    // The workload leg additionally pins a specific Keycloak client -- never ramals-core-workload,
    // which is a different identity for the opposite call direction.
    assertThat(properties.getWorkloadClientId()).isEqualTo("ramals-ai-workload");
    assertThat(properties.getWorkloadClientId()).isNotEqualTo("ramals-core-workload");
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

    assertThat(properties.getWorkloadAudience()).isEqualTo("ramals-mcp");
  }

  @Test
  void changingWorkloadClientIdLeavesWorkloadAudienceUntouched() {
    McpProperties properties = new McpProperties();

    properties.setWorkloadClientId("some-other-client");

    assertThat(properties.getWorkloadAudience()).isEqualTo("ramals-mcp");
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
