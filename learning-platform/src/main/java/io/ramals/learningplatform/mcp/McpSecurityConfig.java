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
 * MCP-1 (M2-ADR-031): protects the MCP transport itself with the existing workload-identity
 * architecture -- reused unchanged, never a second service-authentication system.
 *
 * <p>A dedicated {@link SecurityFilterChain}, matched only to {@code ramals.mcp.endpoint}, ordered
 * ahead of the application's main chain ({@code SecurityConfig}, left completely untouched) so this
 * one governs the MCP path and the main chain continues to govern everything else exactly as before.
 *
 * <p>This chain validates a Keycloak-issued workload token carrying {@code aud=ramals-ai} -- the
 * same audience, issuer and JWKS trust M1-ADR-003 already established for Java's own outbound call to
 * {@code ramals-ai} — reused here for the reverse direction's transport-level authentication, exactly
 * as M2-ADR-031 §A.1 requires ("reuse the existing workload-identity architecture... do not invent
 * another service-authentication system"). It is deliberately a <em>different</em> audience than the
 * main API's {@code ramals-api} (`application.yml`'s own {@code RAMALS_OIDC_AUDIENCE}), so a learner
 * token can never authenticate here and a workload token can never authenticate to the learner-facing
 * API -- the same audience-separation mechanism M1-ADR-003 relies on, applied symmetrically.
 *
 * <p><b>This chain governs transport/session authentication only.</b> It has no opinion about
 * delegated learner context (M2-ADR-031 §D) -- MCP-1 has no learner-scoped capability to gate, so no
 * delegated-context check is wired into any live request path yet; {@link
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator} exists and is tested
 * independently, ready for a future capability to call.
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
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
    return decoder;
  }

  /** {@code aud} must contain the configured workload audience ({@code ramals-ai} by default) --
   * never merely a token that happens to be otherwise valid for {@code ramals-api} or any other
   * audience. This is the exact mechanism, not a new one: the same audience-separation check
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
