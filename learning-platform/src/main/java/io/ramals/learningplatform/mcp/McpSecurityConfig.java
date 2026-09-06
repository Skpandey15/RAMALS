package io.ramals.learningplatform.mcp;

import io.ramals.learningplatform.security.SecurityDenialHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP-1 (M2-ADR-031): protects the MCP transport with a Keycloak-issued workload token authenticating
 * <em>{@code ramals-ai} calling Java</em> -- a direction M1-ADR-003 never covered, and never satisfied
 * by reusing M1-ADR-003's own {@code ramals-core-workload}/{@code aud=ramals-ai} credential, which
 * authenticates the opposite direction (Java calling {@code ramals-ai}) and whose secret only Java
 * ever holds. {@code ramals-ai} has no way to mint a {@code ramals-core-workload} token, so this chain
 * validates a dedicated, separate Keycloak client instead -- see {@link McpProperties} for exactly
 * which one and why.
 *
 * <p>A dedicated {@link SecurityFilterChain}, matched only to {@code ramals.mcp.endpoint}, ordered
 * ahead of the application's main chain ({@code SecurityConfig}, left completely untouched) so this
 * one governs the MCP path and the main chain continues to govern everything else exactly as before.
 *
 * <p>Two independent checks gate this workload token, both required:
 * <ol>
 *   <li><b>Audience</b> ({@link McpProperties#getWorkloadAudience()}, default {@code ramals-mcp}) --
 *       names Java's MCP transport as the intended receiver. Rejects, among others, a replayed
 *       {@code ramals-core-workload} token (audienced {@code ramals-ai}) and a learner token
 *       (audienced {@code ramals-api}).
 *   <li><b>Authorized party</b> ({@link McpProperties#getWorkloadClientId()}, default {@code
 *       ramals-ai-workload}, read from the token's {@code azp} claim, falling back to {@code
 *       client_id}) -- audience alone would admit any client the realm chooses to mint a {@code
 *       ramals-mcp} token for; this pins the door to exactly one workload, mirroring the identical
 *       discipline {@code ramals_ai.security.workload_identity.WorkloadTokenVerifier} already applies
 *       to M1-ADR-003's own direction.
 * </ol>
 *
 * <p><b>This chain governs transport/session authentication only.</b> It has no opinion about
 * delegated learner context (M2-ADR-031 §D) -- MCP-1 has no learner-scoped capability to gate, so no
 * delegated-context check is wired into any live request path yet; {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator} exists and is tested
 * independently, ready for a future capability to call. The two credential types are never conflated
 * even though their audiences may share the literal {@code ramals-mcp}: this chain only ever accepts
 * a Keycloak-issued, RS256/JWKS-verified token; a Java-self-issued, HS256/HMAC-signed delegated-context
 * token cannot satisfy it (wrong issuer, unverifiable signature) even if its audience matches.
 */
@Configuration
@ConditionalOnProperty(prefix = "ramals.mcp", name = "enabled", havingValue = "true")
public class McpSecurityConfig {

  /**
   * Built from a JWK set URI, never {@code NimbusJwtDecoder.withIssuerLocation(...)}. The latter
   * performs OIDC provider discovery -- a real network call to the issuer's own {@code
   * .well-known/openid-configuration} -- synchronously inside {@code build()}, which would run
   * eagerly at application-context startup and couple MCP bean construction to the identity
   * provider's availability at boot, exactly the coupling {@code AiClientConfiguration}'s own
   * "unconfigured means absent, never a startup failure" discipline exists to avoid (confirmed
   * directly: constructing this via {@code withIssuerLocation(...)} makes this project's own test
   * suite fail context startup without a live Keycloak reachable). {@code withJwkSetUri(...)} performs
   * no network call at all here; the JWK set itself is fetched, and cached, only lazily on first
   * actual token decode.
   */
  @Bean
  JwtDecoder mcpWorkloadJwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
      McpProperties properties) {
    // Keycloak's own standard, stable JWKS path for a realm -- the same certs endpoint the platform
    // already trusts implicitly via issuer-based discovery elsewhere.
    String jwkSetUri = issuerUri + "/protocol/openid-connect/certs";
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
    OAuth2TokenValidator<Jwt> withAudience = mcpWorkloadAudienceValidator(properties.getWorkloadAudience());
    OAuth2TokenValidator<Jwt> withPrincipal = mcpWorkloadPrincipalValidator(properties.getWorkloadClientId());
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience, withPrincipal));
    return decoder;
  }

  /** {@code aud} must contain the configured workload audience ({@code ramals-mcp} by default) --
   * never merely a token that happens to be otherwise valid for {@code ramals-api} (the learner-facing
   * API) or {@code ramals-ai} (M1-ADR-003's own, opposite-direction, Java-to-{@code ramals-ai}
   * credential). This is the exact mechanism, not a new one: the same audience-separation check
   * {@code application.yml}'s own {@code spring.security.oauth2.resourceserver.jwt.audiences}
   * property already performs for the main API, built by hand here because that property only
   * configures Spring Boot's single default resource server. Package-visible (not private) so its
   * own tests can call it directly against a hand-built {@link Jwt}, matching the same pattern
   * {@code SecurityContractTests} already uses for the main API's own audience check. */
  static OAuth2TokenValidator<Jwt> mcpWorkloadAudienceValidator(String requiredAudience) {
    return token -> {
      if (token.getAudience() != null && token.getAudience().contains(requiredAudience)) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(new OAuth2Error(
          "invalid_token", "The token audience does not include " + requiredAudience, null));
    };
  }

  /**
   * The token's authorized party must name the expected Keycloak client (default
   * {@code ramals-ai-workload}) -- read from {@code azp}, Keycloak's standard claim for the client a
   * client-credentials-grant token was issued to, falling back to {@code client_id} for parity with
   * {@code ramals_ai.security.workload_identity.WorkloadTokenVerifier}'s own claim precedence.
   * Audience alone would admit any client the realm mints a {@code ramals-mcp} token for; this closes
   * that gap. Package-visible for the same direct-unit-test reason as {@link
   * #mcpWorkloadAudienceValidator}.
   */
  static OAuth2TokenValidator<Jwt> mcpWorkloadPrincipalValidator(String expectedClientId) {
    return token -> {
      String clientId = token.getClaimAsString("azp");
      if (clientId == null || clientId.isBlank()) {
        clientId = token.getClaimAsString("client_id");
      }
      if (expectedClientId.equals(clientId)) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(new OAuth2Error(
          "invalid_token", "The token's authorized party is not the expected MCP workload client", null));
    };
  }

  @Bean
  @Order(1)
  SecurityFilterChain mcpSecurityFilterChain(
      HttpSecurity http, JwtDecoder mcpWorkloadJwtDecoder, SecurityDenialHandler denialHandler,
      McpProperties properties) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        new io.ramals.learningplatform.security.KeycloakRealmRoleConverter());

    http
        .securityMatcher(properties.getEndpoint())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer
            .jwt(jwt -> jwt.decoder(mcpWorkloadJwtDecoder).jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(denialHandler)
            .accessDeniedHandler(denialHandler))
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(denialHandler)
            .accessDeniedHandler(denialHandler));
    return http.build();
  }
}
