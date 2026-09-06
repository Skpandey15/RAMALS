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
 * <p><b>Two directions, two distinct workload identities -- neither is {@code ramals-core-workload}
 * reused.</b> M1-ADR-003's {@code ramals-core-workload} client (Keycloak) authenticates
 * <em>Java calling ramals-ai</em>, {@code aud=ramals-ai}; only Java ever holds that client's secret,
 * so {@code ramals-ai} could never present a token audienced {@code ramals-ai} back to Java -- it has
 * no way to mint one. The MCP transport instead validates a <em>different</em>, dedicated Keycloak
 * client -- conceptually {@code ramals-ai-workload}, {@code serviceAccountsEnabled=true}, its own
 * audience mapper set to {@link #workloadAudience} (default {@code ramals-mcp}, not {@code
 * ramals-ai}) -- that authenticates <em>ramals-ai calling Java</em>, the reverse direction M1-ADR-003
 * never covered. {@link #workloadClientId} additionally pins the expected authorized-party
 * ({@code azp}) claim, so audience alone (which any client the realm chooses to mint a {@code
 * ramals-mcp} token for would satisfy) is never sufficient by itself -- mirroring the identical
 * discipline {@code ramals_ai.security.workload_identity.WorkloadTokenVerifier} already applies to
 * M1-ADR-003's own direction.
 *
 * <p>{@link #workloadAudience} and {@link DelegatedContext#audience} may legitimately share the same
 * literal value ({@code ramals-mcp} both name "the receiver is Java's MCP transport"), but the two
 * credentials they gate are never conflated: a workload token is Keycloak-issued, RS256/JWKS-verified,
 * and proves only <em>which service is calling</em>; a delegated-context token is Java-self-issued,
 * HS256/HMAC-verified, and proves <em>which already-authorized learner interaction and capability
 * scope</em> this call may act for. Neither substitutes for the other -- see {@link
 * io.ramals.learningplatform.mcp.McpSecurityConfig} and {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator} for where each is enforced
 * independently.
 */
@Validated
@ConfigurationProperties(prefix = "ramals.mcp")
public class McpProperties {

  /** Master switch. No MCP bean of any kind is created while this is {@code false}. */
  private boolean enabled = false;

  /** The single governed MCP endpoint path (Streamable HTTP). */
  private String endpoint = "/mcp";

  /**
   * The audience a workload token presented to the MCP transport must carry.
   *
   * <p>Defaults to {@code ramals-mcp} -- naming <em>Java's MCP transport</em> as the intended
   * receiver, exactly the same audience-as-receiver convention every other client in this realm
   * already follows ({@code ramals-web-ui}/{@code ramals-core-workload} name {@code ramals-api}/
   * {@code ramals-ai} as their own receivers). It is deliberately <b>not</b> {@code ramals-ai}: that
   * audience names {@code ramals-ai} as the receiver, which is correct for M1-ADR-003's own Java-to-
   * {@code ramals-ai} direction and wrong for this one. A stray {@code ramals-ai}-audienced token
   * presented here (e.g. a replayed {@code ramals-core-workload} token) is rejected by audience
   * mismatch alone, before {@link #workloadClientId} is even consulted.
   */
  private String workloadAudience = "ramals-mcp";

  /**
   * The expected authorized-party (Keycloak {@code azp}, falling back to {@code client_id}) claim a
   * workload token presented to the MCP transport must carry.
   *
   * <p>Audience alone would admit any client the realm chooses to mint a {@link #workloadAudience}
   * token for; pinning the client id keeps the door open to exactly one workload -- the same
   * reasoning, and the same claim precedence, {@code
   * ramals_ai.security.workload_identity.WorkloadTokenVerifier} already applies for M1-ADR-003's own
   * direction. Defaults to {@code ramals-ai-workload}, the dedicated Keycloak client this transport
   * expects -- never {@code ramals-core-workload}, which is a different identity for the opposite
   * call direction and does not hold this audience's secret.
   */
  private String workloadClientId = "ramals-ai-workload";

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

  public String getWorkloadClientId() {
    return workloadClientId;
  }

  public void setWorkloadClientId(String workloadClientId) {
    this.workloadClientId = workloadClientId;
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
     * The audience a delegated learner-context token must carry. May coincide, as a literal string,
     * with {@link McpProperties#workloadAudience} (both legitimately name "the receiver is Java's
     * MCP transport") -- what keeps the two credentials from being conflated is mechanism, not the
     * audience string: this one is Java-self-issued and HS256/HMAC-verified by {@link
     * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator}, never Keycloak-issued or
     * JWKS-verified, and a workload token can never satisfy it (wrong issuer, wrong signature
     * mechanism entirely) even if its audience happens to match.
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
