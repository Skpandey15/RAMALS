package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Keycloak realm mints the claims the API depends on. The API authorizes learner
 * resources by the learner_id claim and MFA by acr/amr, so the realm must be configured to emit
 * them; this guards against the deployed realm silently dropping those mappers.
 */
class RealmHardeningTests {

  @Test
  void realmMintsLearnerIdAudienceAndMfaSignals() throws IOException {
    String realm = realm();
    assertThat(realm)
        .contains("\"ramals-api-audience\"")
        .contains("oidc-usermodel-attribute-mapper")
        .contains("\"claim.name\": \"learner_id\"")
        .contains("\"acr.loa.map\"")
        .contains("\"otpPolicyType\"");
  }

  private String realm() throws IOException {
    Path fromModule = Path.of("..", "infrastructure", "docker", "keycloak", "ramals-realm.json");
    Path realm = Files.exists(fromModule)
        ? fromModule
        : Path.of("infrastructure", "docker", "keycloak", "ramals-realm.json");
    assertThat(Files.exists(realm)).as("realm file at %s", realm.toAbsolutePath()).isTrue();
    return Files.readString(realm, StandardCharsets.UTF_8);
  }
}
