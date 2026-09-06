package io.ramals.learningplatform.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * M2-ADR-031 / MCP-1 configuration. Safe default throughout: {@link #enabled} is {@code false}, so a
 * deployment that never sets {@code RAMALS_MCP_ENABLED} gets no MCP server, no delegated-context
 * configuration surface, and no new attack surface -- exactly the same "absent means safely off"
 * discipline {@code AiClientConfiguration} already holds the AI-plane client to.
 *
 * <p>{@link #workloadAudience} and {@link DelegatedContext#audience} are deliberately distinct
 * concerns, on purpose kept as two separately-named fields rather than one: the former is the
 * audience the MCP transport's own <em>workload</em> authentication must carry (reused unchanged
 * from M1-ADR-003, defaulting to the existing {@code ramals-ai} audience); the latter is the
 * audience a <em>delegated learner-context</em> credential must carry ({@code ramals-mcp}, a new
 * audience M2-ADR-031 introduces). A workload token proves only "this caller is the authenticated
 * {@code ramals-ai} workload" -- never "this workload may act for learner X" -- so the two
 * credentials, and the two audiences that gate them, must never be substitutable for one another.
 * See {@link io.ramals.learningplatform.mcp.McpSecurityConfig} and {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator} for where each is enforced.
 */
@Validated
@ConfigurationProperties(prefix = "ramals.mcp")
public class McpProperties {

  /** Master switch. No MCP bean of any kind is created while this is {@code false}. */
  private boolean enabled = false;

  /** The single governed MCP endpoint path (Streamable HTTP). */
  private String endpoint = "/mcp";

  /**
   * The audience workload authentication for the MCP transport itself must carry.
   *
   * <p>Deliberately defaults to {@code ramals-ai} -- the existing M1-ADR-003 workload audience,
   * reused unchanged for the MCP transport's own authentication leg (M2-ADR-031 §A.1: "reuse the
   * existing workload-identity architecture... do not invent another service-authentication
   * system"). This authenticates <em>which workload is calling</em>; it says nothing about which
   * learner, if any, that call is acting for -- that is exactly what {@link DelegatedContext}
   * governs, under its own, deliberately different, {@link DelegatedContext#audience}.
   */
  private String workloadAudience = "ramals-ai";

  private final DelegatedContext delegatedContext = new DelegatedContext();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getWorkloadAudience() {
    return workloadAudience;
  }

  public void setWorkloadAudience(String workloadAudience) {
    this.workloadAudience = workloadAudience;
  }

  public DelegatedContext getDelegatedContext() {
    return delegatedContext;
  }

  /**
   * The delegated learner-context credential's own configuration: a short-lived, HMAC-signed token
   * Java itself issues and verifies (M2-ADR-031), carrying no learner-state data, gated by its own
   * {@link #audience} ({@code ramals-mcp}) -- never the {@link McpProperties#workloadAudience}.
   *
   * <p>Modeled on {@code ResultEncryptionKeyProperties}: key material lives in a key-id-to-base64
   * map with a separately-named active id, every field defaults to empty/blank, and {@link
   * #toString()} redacts it. <strong>No key ships in the repository, none is generated at
   * runtime, and none is required while MCP is disabled</strong> -- {@link
   * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextSigningKeys} (the one reader of
   * {@link #keys}) resolves and validates this material lazily, on first actual use, exactly like
   * {@code EnvironmentResultEncryptionKeyProvider} does for Contract B: a deployment that turns MCP
   * on without configuring a key fails the first time something needs one, not at every startup for
   * a capability nothing yet calls.
   */
  public static class DelegatedContext {

    /** Self-asserted issuer claim; Java is both sole issuer and sole verifier (M2-ADR-031). */
    private String issuer = "ramals-learning-platform";

    /**
     * The audience a delegated learner-context token must carry. Deliberately not {@code
     * ramals-ai}: a workload token that merely authenticates the {@code ramals-ai} caller must
     * never be accepted as authorization to act for a specific learner, and vice versa.
     */
    private String audience = "ramals-mcp";

    /** How long an issued delegated context remains valid, bound to the authorizing interaction. */
    private long ttlSeconds = 120;

    /** Key id new delegated-context tokens are signed under. Empty until configured. */
    private String activeKeyId = "";

    /**
     * Key id to base64 HMAC key material, holding a retired key alongside the active one during a
     * rotation window. A map, not two flat fields, for the same reason {@code
     * ResultEncryptionKeyProperties.keys} is: {@link
     * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator} must keep accepting a
     * just-retired key's signature for tokens already issued under it until they expire.
     */
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getIssuer() {
      return issuer;
    }

    public void setIssuer(String issuer) {
      this.issuer = issuer;
    }

    public String getAudience() {
      return audience;
    }

    public void setAudience(String audience) {
      this.audience = audience;
    }

    public long getTtlSeconds() {
      return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
      this.ttlSeconds = ttlSeconds;
    }

    public String getActiveKeyId() {
      return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
      this.activeKeyId = activeKeyId == null ? "" : activeKeyId.trim();
    }

    public Map<String, String> getKeys() {
      return keys;
    }

    public void setKeys(Map<String, String> keys) {
      this.keys = keys == null ? new LinkedHashMap<>() : keys;
    }

    /**
     * Ids and a count, never material -- overridden for the same reason {@code
     * ResultEncryptionKeyProperties#toString()} is: this configuration object reaches logs and
     * diagnostic endpoints by default, which would otherwise publish every signing key configured.
     */
    @Override
    public String toString() {
      return "DelegatedContext[issuer=" + issuer + ", audience=" + audience
          + ", ttlSeconds=" + ttlSeconds + ", activeKeyId=" + activeKeyId
          + ", knownKeyIds=" + keys.keySet() + ", material=REDACTED]";
    }
  }
}
