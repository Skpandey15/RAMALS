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

  @Test
  void realmDeclaresTheWorkloadClientWithTheAiAudience() throws IOException {
    // M1-ADR-003. The live drill in scripts/validation/workload-identity-e2e.py proves the mapper
    // actually mints the claim; this proves the committed realm still declares it, so a regression
    // fails in CI rather than waiting for someone to run a drill against a live Keycloak.
    String realm = realm();
    assertThat(realm)
        .contains("\"ramals-core-workload\"")
        .contains("\"ramals-ai-audience\"")
        .contains("\"included.client.audience\": \"ramals-ai\"");
  }

  @Test
  void workloadClientCannotObtainAUserToken() throws IOException {
    // The workload identity must never be able to represent a person: that separation is the only
    // thing distinguishing "the learner asked for this" from "a model decided to do this".
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
    // The client is confidential; its secret belongs to the environment's secret management. A
    // committed secret would be a credential in source control that also happens to work.
    assertThat(clientBlock("ramals-core-workload"))
        .as("the workload client secret must not be committed")
        .doesNotContain("\"secret\"");
  }

  /** The JSON text of one client definition, so assertions cannot pass on a neighbouring client. */
  private String clientBlock(String clientId) throws IOException {
    String realm = realm();
    int start = realm.indexOf("\"clientId\": \"" + clientId + "\"");
    assertThat(start).as("client %s present in the realm", clientId).isNotNegative();
    int end = realm.indexOf("\"clientId\":", start + 1);
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
