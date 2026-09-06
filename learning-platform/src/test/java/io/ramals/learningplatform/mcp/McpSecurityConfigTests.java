package io.ramals.learningplatform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextSigningKeys;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M2-ADR-031 (MCP-1): the MCP transport is protected by a Keycloak-issued workload token
 * authenticating <em>{@code ramals-ai} calling Java</em> -- {@code aud=ramals-mcp} and {@code
 * azp=ramals-ai-workload} by default -- and, independently, rejects a request that does not carry a
 * workload token at all, before anything reaches the MCP protocol handler.
 *
 * <p>This is deliberately <b>not</b> M1-ADR-003's own {@code ramals-core-workload}/{@code
 * aud=ramals-ai} credential reused: that one authenticates the opposite direction (Java calling
 * {@code ramals-ai}), only Java ever holds its secret, and {@link
 * #ramalsCoreWorkloadTokenIntendedForRamalsAiIsRejected()} proves it is rejected here exactly like
 * any other wrong-audience token. {@link
 * #workloadAudienceValidatorAcceptsOnlyTheConfiguredAudience()} and {@link
 * #principalValidatorAcceptsOnlyTheExpectedWorkloadClient()} together prove both independent checks
 * M2-ADR-031 §A.1 requires: audience alone is never sufficient, and the authorized-party (principal)
 * claim is checked separately. {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidatorTests#workloadAudiencedTokenCannotBeUsedAsDelegatedContext()}
 * proves the converse direction: a workload-audienced token cannot be used as a delegated context.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:mcp-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false",
    "ramals.mcp.enabled=true"
})
@AutoConfigureMockMvc
class McpSecurityConfigTests {

  @Autowired
  MockMvc mockMvc;

  @Autowired
  DelegatedLearnerContextSigningKeys delegatedLearnerContextSigningKeys;

  @Test
  void mcpEndpointRejectsARequestWithNoWorkloadToken() throws Exception {
    mockMvc.perform(post("/mcp").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Proves "no production key required when MCP is disabled" extends correctly to "none required at
   * startup even when enabled": this whole context starts successfully with {@code
   * ramals.mcp.enabled=true} and zero {@code ramals.mcp.delegated-context.*} configuration, exactly
   * like {@code EnvironmentResultEncryptionKeyProvider} -- the bean exists, but is inert until
   * something actually asks it for key material, which MCP-1 never does.
   */
  @Test
  void delegatedContextSigningKeysBeanExistsButIsInertWithoutConfiguration() {
    assertThatThrownBy(delegatedLearnerContextSigningKeys::activeKeyId)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active-key-id is not configured");
  }

  // -- audience: which direction is this token for -------------------------------------------------

  @Test
  void workloadAudienceValidatorAcceptsOnlyTheConfiguredAudience() {
    OAuth2TokenValidator<Jwt> validator = McpSecurityConfig.mcpWorkloadAudienceValidator("ramals-mcp");

    OAuth2TokenValidatorResult correct = validator.validate(token(List.of("ramals-mcp"), "ramals-ai-workload"));
    assertThat(correct.hasErrors()).isFalse();

    // ramals-api is the learner-facing API's own audience (test #3: a learner/API token is rejected).
    OAuth2TokenValidatorResult learnerApiAudience =
        validator.validate(token(List.of("ramals-api"), "ramals-web-ui"));
    assertThat(learnerApiAudience.hasErrors()).isTrue();

    OAuth2TokenValidatorResult noAudience = validator.validate(token(null, "ramals-ai-workload"));
    assertThat(noAudience.hasErrors()).isTrue();
  }

  /**
   * Test #1: M1-ADR-003's own {@code ramals-core-workload} credential (Java calling {@code
   * ramals-ai}, {@code aud=ramals-ai}) is a real, valid, Keycloak-issued token -- just for the wrong
   * direction. It must be rejected here exactly like any other wrong audience, never accepted as
   * authenticating the reverse direction it was never issued for.
   */
  @Test
  void ramalsCoreWorkloadTokenIntendedForRamalsAiIsRejected() {
    OAuth2TokenValidator<Jwt> validator = McpSecurityConfig.mcpWorkloadAudienceValidator("ramals-mcp");

    OAuth2TokenValidatorResult result =
        validator.validate(token(List.of("ramals-ai"), "ramals-core-workload"));

    assertThat(result.hasErrors()).isTrue();
  }

  // -- authorized party: which client is this token for, independent of audience ------------------

  /**
   * Test #4: audience alone would admit any client the realm chooses to mint a {@code ramals-mcp}
   * token for. Pinning {@code azp} (falling back to {@code client_id}) closes that gap -- mirroring
   * {@code ramals_ai.security.workload_identity.WorkloadTokenVerifier}'s own claim precedence.
   */
  @Test
  void principalValidatorAcceptsOnlyTheExpectedWorkloadClient() {
    OAuth2TokenValidator<Jwt> validator =
        McpSecurityConfig.mcpWorkloadPrincipalValidator("ramals-ai-workload");

    OAuth2TokenValidatorResult correctPrincipal =
        validator.validate(token(List.of("ramals-mcp"), "ramals-ai-workload"));
    assertThat(correctPrincipal.hasErrors()).isFalse();

    // Correct audience, wrong principal: a different client somehow minted a ramals-mcp-audienced
    // token. Rejected on the principal check alone.
    OAuth2TokenValidatorResult wrongPrincipal =
        validator.validate(token(List.of("ramals-mcp"), "some-other-client"));
    assertThat(wrongPrincipal.hasErrors()).isTrue();

    OAuth2TokenValidatorResult noPrincipal = validator.validate(tokenWithoutAzpOrClientId());
    assertThat(noPrincipal.hasErrors()).isTrue();
  }

  @Test
  void principalValidatorFallsBackToClientIdClaimWhenAzpIsAbsent() {
    OAuth2TokenValidator<Jwt> validator =
        McpSecurityConfig.mcpWorkloadPrincipalValidator("ramals-ai-workload");

    Jwt withClientIdOnly = Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .issuer("http://localhost:8081/realms/ramals")
        .subject("service-account-ramals-ai-workload")
        .claim("client_id", "ramals-ai-workload")
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(Instant.now().plusSeconds(60))
        .audience(List.of("ramals-mcp"))
        .build();

    assertThat(validator.validate(withClientIdOnly).hasErrors()).isFalse();
  }

  /** Combines both checks exactly as {@code mcpWorkloadJwtDecoder} does, proving the full chain. */
  @Test
  void combinedAudienceAndPrincipalChainRequiresBoth() {
    OAuth2TokenValidator<Jwt> chain = new DelegatingOAuth2TokenValidator<>(
        McpSecurityConfig.mcpWorkloadAudienceValidator("ramals-mcp"),
        McpSecurityConfig.mcpWorkloadPrincipalValidator("ramals-ai-workload"));

    assertThat(chain.validate(token(List.of("ramals-mcp"), "ramals-ai-workload")).hasErrors())
        .isFalse();
    // Test #1 again, through the combined chain this time.
    assertThat(chain.validate(token(List.of("ramals-ai"), "ramals-core-workload")).hasErrors())
        .isTrue();
    // Test #4 again, through the combined chain.
    assertThat(chain.validate(token(List.of("ramals-mcp"), "some-other-client")).hasErrors())
        .isTrue();
  }

  // -- cross-credential substitution: never, even when an audience literal coincides ---------------

  /**
   * Test #5: a real, validly-signed delegated-context token (HS256, Java-self-issued) cannot
   * authenticate as the MCP workload, even though its audience may coincide ({@code ramals-mcp}).
   * Verified against a decoder built the same way {@code mcpWorkloadJwtDecoder} is -- from a public
   * key rather than a live JWKS endpoint, so this runs with no network dependency -- proving the
   * structural reason this must fail: JWKS (and any RS256/EC public-key source) never contains an
   * HMAC secret, so an HS256 token can never be verified by it, regardless of audience or claims.
   */
  @Test
  void delegatedContextTokenCannotAuthenticateAsWorkload() throws Exception {
    Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    byte[] hmacKey = new byte[32];
    new SecureRandom().nextBytes(hmacKey);
    String delegatedContextToken = new DelegatedLearnerContextIssuer(
            "ramals-learning-platform", "ramals-mcp", Duration.ofMinutes(2), "current", hmacKey, clock)
        .issue("interaction-1", "learner-ref-1", "KAFKA",
            Set.of("diagnostics.current-domain-report"));

    KeyPair rsaKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    JwtDecoder workloadShapedDecoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) rsaKeyPair.getPublic()).build();

    assertThatThrownBy(() -> workloadShapedDecoder.decode(delegatedContextToken))
        .isInstanceOf(JwtException.class);
  }

  private Jwt token(List<String> audience, String azp) {
    Jwt.Builder builder = Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .issuer("http://localhost:8081/realms/ramals")
        .subject("service-account-" + azp)
        .claim("azp", azp)
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(Instant.now().plusSeconds(60));
    if (audience != null) {
      builder.audience(audience);
    }
    return builder.build();
  }

  private Jwt tokenWithoutAzpOrClientId() {
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .issuer("http://localhost:8081/realms/ramals")
        .subject("service-account-unknown")
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(Instant.now().plusSeconds(60))
        .audience(List.of("ramals-mcp"))
        .build();
  }
}
