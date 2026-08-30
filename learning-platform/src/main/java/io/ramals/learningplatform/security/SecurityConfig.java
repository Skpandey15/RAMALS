package io.ramals.learningplatform.security;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.List;
import io.ramals.learningplatform.observability.TraceContextAccessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter converter, SecurityDenialHandler denialHandler,
      @Qualifier("subjectRateLimiter") TokenBucketRateLimiter subjectRateLimiter,
      RateLimitProperties rateLimitProperties, ObjectMapper objectMapper,
      TraceContextAccessor traceContext)
      throws Exception {
    http
        .cors(withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/registration").permitAll()
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer
            .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
            // Also set on the resource server: it installs its own entry point otherwise, and the
            // denial would bypass the audit and return an empty body.
            .authenticationEntryPoint(denialHandler)
            .accessDeniedHandler(denialHandler))
        // Denials are audited and answered with a Problem Details body carrying the correlation
        // ids (Master Plan §7, §8). Spring Security's defaults return an empty body.
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(denialHandler)
            .accessDeniedHandler(denialHandler))
        .headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
            .frameOptions(frame -> frame.deny())
            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
            .contentTypeOptions(Customizer.withDefaults())
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(63072000))
            .permissionsPolicyHeader(permissions -> permissions
                .policy("geolocation=(), camera=(), microphone=(), payment=(), usb=()")));
    // Per-learner fair-use limiting sits AFTER token validation, so the subject it keys on has
    // actually been verified. It is constructed here rather than exposed as a bean so Boot does not
    // also auto-register it as a plain servlet filter outside the security chain.
    http.addFilterAfter(
        new SubjectRateLimitFilter(
            subjectRateLimiter, rateLimitProperties, objectMapper, traceContext),
        BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /** Pre-authentication tier: keyed on client IP, sized to shed floods, not to police individuals. */
  @Bean
  TokenBucketRateLimiter ipRateLimiter(RateLimitProperties properties) {
    return new TokenBucketRateLimiter(properties);
  }

  /** Post-authentication tier: keyed on the verified token subject; the per-learner fair-use limit. */
  @Bean
  TokenBucketRateLimiter subjectRateLimiter(RateLimitProperties properties) {
    return new TokenBucketRateLimiter(properties.getSubject());
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
    return converter;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${ramals.security.web-origin}") String webOrigin) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(webOrigin));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of(
        "Authorization", "Content-Type", "X-Interaction-ID", "Idempotency-Key"));
    configuration.setExposedHeaders(List.of(
        "X-Interaction-ID", "X-Request-ID", "X-Trace-ID"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
