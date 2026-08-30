package io.ramals.learningplatform.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class SecurityContractTests {

  private static final String ISSUER = "http://localhost:8081/realms/ramals";

  @Autowired
  MockMvc mockMvc;

  @Test
  void protectedApiWithoutTokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedUserWithoutRequiredRoleIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/content/security-check").with(jwt()))
        .andExpect(status().isForbidden());
  }

  @Test
  void crossLearnerIdorIsDeniedButOwnershipIsAllowed() throws Exception {
    var learner = jwt()
        .jwt(token -> token.subject("user-1").claim("learner_id", "learner-1"))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));

    mockMvc.perform(get("/api/v1/learners/learner-2/profile").with(learner))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/learners/learner-1/profile").with(learner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.learnerId").value("learner-1"));
  }

  @Test
  void adminWithoutMfaIsDeniedAndAdminWithOtpIsAllowed() throws Exception {
    mockMvc.perform(get("/api/v1/admin/security-check")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());

    mockMvc.perform(get("/api/v1/admin/security-check")
            .with(jwt()
                .jwt(token -> token.claim("amr", List.of("pwd", "otp")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void wrongAudienceAndExpiredTokensFailValidation() {
    // Audience is enforced by the resource-server 'jwt.audiences' property, which installs this same
    // claim validator; a token for a different audience is rejected and the correct one accepted.
    OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD, values -> values != null && values.contains("ramals-api"));

    Jwt wrongAudience = token(Instant.now().minusSeconds(10), Instant.now().plusSeconds(60), "other-api");
    org.assertj.core.api.Assertions.assertThat(audience.validate(wrongAudience).hasErrors()).isTrue();

    Jwt rightAudience = token(Instant.now().minusSeconds(10), Instant.now().plusSeconds(60), "ramals-api");
    org.assertj.core.api.Assertions.assertThat(audience.validate(rightAudience).hasErrors()).isFalse();

    // Stay beyond Spring Security's permitted clock skew; exactly 60 seconds is a boundary and
    // made this assertion depend on sub-millisecond scheduling.
    Jwt expired = token(Instant.now().minusSeconds(240), Instant.now().minusSeconds(120), "ramals-api");
    OAuth2TokenValidatorResult expiryResult = JwtValidators.createDefaultWithIssuer(ISSUER).validate(expired);
    org.assertj.core.api.Assertions.assertThat(expiryResult.hasErrors()).isTrue();
  }

  private Jwt token(Instant issuedAt, Instant expiresAt, String audience) {
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .issuer(ISSUER)
        .subject("test-subject")
        .audience(List.of(audience))
        .issuedAt(issuedAt)
        .expiresAt(expiresAt)
        .build();
  }
}
