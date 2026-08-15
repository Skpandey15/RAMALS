package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTests {

  @Test
  void mapsOnlyApprovedRealmRoles() {
    Jwt jwt = Jwt.withTokenValue("test")
        .header("alg", "none")
        .subject("subject")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("realm_access", Map.of("roles", List.of("LEARNER", "unknown-role")))
        .build();

    assertThat(new KeycloakRealmRoleConverter().convert(jwt))
        .extracting("authority")
        .contains("ROLE_LEARNER")
        .doesNotContain("ROLE_unknown-role");
  }
}
