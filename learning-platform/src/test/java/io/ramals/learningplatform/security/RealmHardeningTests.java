package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Keycloak realm mints the claims the API depends on and preserves workload-identity
 * separation for AI, registration, and interactive identity administration.
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

  @Test
  void realmDeclaresTheWorkloadClientWithTheAiAudience() throws IOException {
    String realm = realm();
    assertThat(realm)
        .contains("\"ramals-core-workload\"")
        .contains("\"ramals-ai-audience\"")
        .contains("\"included.client.audience\": \"ramals-ai\"");
  }

  @Test
  void workloadClientCannotObtainAUserToken() throws IOException {
    String workloadClient = clientBlock("ramals-core-workload");
    assertThat(workloadClient)
        .contains("\"serviceAccountsEnabled\": true")
        .contains("\"standardFlowEnabled\": false")
        .contains("\"directAccessGrantsEnabled\": false")
        .contains("\"implicitFlowEnabled\": false")
        .contains("\"publicClient\": false");
  }

  @Test
  void realmNeverCommitsTheWorkloadSecret() throws IOException {
    assertThat(clientBlock("ramals-core-workload"))
        .as("the workload client secret must not be committed")
        .doesNotContain("\"secret\"");
  }

  @Test
  void identityAdministrationUsesADifferentConfidentialClientFromRegistration() throws IOException {
    String identityAdmin = clientBlock("ramals-identity-admin");
    assertThat(identityAdmin)
        .contains("\"publicClient\": false")
        .contains("\"serviceAccountsEnabled\": true")
        .contains("\"standardFlowEnabled\": false")
        .contains("\"directAccessGrantsEnabled\": false")
        .doesNotContain("\"secret\"");
    assertThat(identityAdmin).doesNotContain("ramals-registration-admin");
  }

  @Test
  void identityAdminHasOnlyUserManagementRealmPermissions() throws IOException {
    String realm = realm();
    int userStart = realm.indexOf("\"serviceAccountClientId\": \"ramals-identity-admin\"");
    assertThat(userStart).as("identity-admin service-account user present").isNotNegative();
    int nextUser = realm.indexOf("\"serviceAccountClientId\":", userStart + 1);
    String serviceAccount = nextUser < 0 ? realm.substring(userStart) : realm.substring(userStart, nextUser);
    assertThat(serviceAccount)
        .contains("\"manage-users\"")
        .contains("\"view-users\"")
        .doesNotContain("\"manage-realm\"")
        .doesNotContain("\"manage-clients\"");
  }

  /** The JSON text of one client definition, so assertions cannot pass on a neighbouring client. */
  private String clientBlock(String clientId) throws IOException {
    String realm = realm();
    int start = realm.indexOf("\"clientId\": \"" + clientId + "\"");
    assertThat(start).as("client %s present in the realm", clientId).isNotNegative();
    int end = realm.indexOf("\"clientId\":", start + 1);
    if (end < 0) {
      end = realm.indexOf("\"users\":", start);
    }
    return end < 0 ? realm.substring(start) : realm.substring(start, end);
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
