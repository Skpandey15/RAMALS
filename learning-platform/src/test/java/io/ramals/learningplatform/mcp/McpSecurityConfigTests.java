package io.ramals.learningplatform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextSigningKeys;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M2-ADR-031 (MCP-1): the MCP transport is protected by the existing workload-identity architecture,
 * requiring {@code aud=ramals-ai} (reused unchanged from M1-ADR-003) -- and, independently, rejects a
 * request that does not carry a workload token at all, before anything reaches the MCP protocol
 * handler.
 *
 * <p>{@link #workloadAudienceValidatorAcceptsOnlyTheConfiguredAudience()} additionally proves the
 * audience separation M2-ADR-031 requires from this side: a token bearing the <em>delegated
 * learner-context</em> audience ({@code ramals-mcp}) is rejected here exactly like any other wrong
 * audience -- a credential that authorizes learner-scoped access can never itself authenticate the
 * MCP transport's workload leg. {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidatorTests#workloadAudiencedTokenCannotBeUsedAsDelegatedContext()}
 * proves the converse.
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

  @Test
  void workloadAudienceValidatorAcceptsOnlyTheConfiguredAudience() {
    OAuth2TokenValidator<Jwt> validator = McpSecurityConfig.mcpWorkloadAudienceValidator("ramals-ai");

    OAuth2TokenValidatorResult correct = validator.validate(token(List.of("ramals-ai")));
    assertThat(correct.hasErrors()).isFalse();

    OAuth2TokenValidatorResult wrongAudience = validator.validate(token(List.of("ramals-api")));
    assertThat(wrongAudience.hasErrors()).isTrue();

    // ramals-mcp is the *delegated learner-context* credential's own audience (M2-ADR-031) -- it
    // must never double as workload authentication for the MCP transport itself.
    OAuth2TokenValidatorResult delegatedContextAudienceRejected =
        validator.validate(token(List.of("ramals-mcp")));
    assertThat(delegatedContextAudienceRejected.hasErrors()).isTrue();

    OAuth2TokenValidatorResult noAudience = validator.validate(token(null));
    assertThat(noAudience.hasErrors()).isTrue();
  }

  private Jwt token(List<String> audience) {
    Jwt.Builder builder = Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .issuer("http://localhost:8081/realms/ramals")
        .subject("ramals-core-workload")
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(Instant.now().plusSeconds(60));
    if (audience != null) {
      builder.audience(audience);
    }
    return builder.build();
  }
}
