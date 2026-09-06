package io.ramals.learningplatform.mcp;

import java.util.Set;

/**
 * The explicit, deny-by-default allowlist of RAMALS MCP business capabilities the server actually
 * offers -- independent of, and checked in addition to, whatever a delegated learner-context token
 * itself claims. A token's own capability allowlist (M2-ADR-031) says what one specific interaction
 * may invoke; this registry says what the server offers at all. Both must agree before any future
 * dispatch may proceed -- a compromised or malformed token claiming a capability the server never
 * registered is still refused, here, independent of the token's own validity.
 *
 * <p>MCP-1 registered zero business capabilities. MCP-2 adds exactly five, all read-only and
 * learner-scoped -- {@link #mcp2()} is the only additional factory this class exposes, and it is a
 * hardcoded literal set, not derived from a bean count, a package scan, or the tool list the MCP
 * server itself advertises: the registry and the protocol-level tool registration are two
 * independent things this class deliberately keeps unable to drift into agreeing by construction
 * alone.
 *
 * <p>There is no reflection scan, no annotation-driven discovery, no auto-publication of a Spring
 * bean or controller method, and no generic "execute"/arbitrary-method-name path. A future capability
 * requires an explicit source-code addition to whatever constructs this registry -- there is no other
 * way to register one.
 */
public final class McpCapabilityRegistry {

  private final Set<String> registeredCapabilities;

  private McpCapabilityRegistry(Set<String> registeredCapabilities) {
    this.registeredCapabilities = Set.copyOf(registeredCapabilities);
  }

  /** MCP-1's own state: no business capability is registered. Retained for tests that still need to
   * prove the empty/deny-by-default state on its own terms. */
  public static McpCapabilityRegistry empty() {
    return new McpCapabilityRegistry(Set.of());
  }

  /** MCP-2's state: exactly five read-only, learner-scoped capabilities -- no wildcard, no other
   * business capability, no proposal/write tool of any kind. */
  public static McpCapabilityRegistry mcp2() {
    return new McpCapabilityRegistry(Set.of(
        "diagnostics.current-domain-report",
        "diagnostics.attempt-report",
        "diagnostics.longitudinal-summary",
        "diagnostics.misconception-longitudinal-detail",
        "mastery.current"));
  }

  /** Whether the server offers this exact, allowlisted capability name at all -- independent of any
   * token's own claims. */
  public boolean isRegistered(String capabilityName) {
    return registeredCapabilities.contains(capabilityName);
  }

  /** The complete registered set, for diagnostics/tests only -- never mutable, never exposed as a
   * way to add to it. */
  public Set<String> registeredCapabilities() {
    return registeredCapabilities;
  }
}
