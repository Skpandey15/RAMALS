package io.ramals.learningplatform.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** MCP-1: the capability registry starts, and for this PR remains, empty. */
class McpCapabilityRegistryTests {

  @Test
  void emptyRegistryHasNoRegisteredCapabilities() {
    McpCapabilityRegistry registry = McpCapabilityRegistry.empty();
    assertThat(registry.registeredCapabilities()).isEmpty();
  }

  @Test
  void emptyRegistryDeniesEveryCapabilityName() {
    McpCapabilityRegistry registry = McpCapabilityRegistry.empty();
    assertThat(registry.isRegistered("diagnostics.current-domain-report")).isFalse();
    assertThat(registry.isRegistered("diagnostics.attempt-report")).isFalse();
    assertThat(registry.isRegistered("diagnostics.longitudinal-summary")).isFalse();
    assertThat(registry.isRegistered("mastery.current")).isFalse();
    assertThat(registry.isRegistered("anything-at-all")).isFalse();
    assertThat(registry.isRegistered("mcp:*")).isFalse();
    assertThat(registry.isRegistered("")).isFalse();
  }
}
