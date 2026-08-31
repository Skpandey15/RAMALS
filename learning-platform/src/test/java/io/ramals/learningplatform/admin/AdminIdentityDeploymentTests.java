package io.ramals.learningplatform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminIdentityDeploymentTests {

  @Test
  void dockerComposeReconcilesAndInjectsTheDedicatedIdentityAdminSecret() throws IOException {
    String compose = readRepoFile("infrastructure/docker/compose.yml");

    assertThat(compose)
        .contains("keycloak-identity-admin-reconcile:")
        .contains("/opt/keycloak/bin/reconcile-identity-admin.sh")
        .contains("RAMALS_ADMIN_IDENTITY_CLIENT_SECRET: ${RAMALS_ADMIN_IDENTITY_CLIENT_SECRET:?")
        .contains("condition: service_completed_successfully");
  }

  @Test
  void promotedComposeAlsoRequiresSuccessfulIdentityAdminReconciliation() throws IOException {
    String compose = readRepoFile("deploy/compose.deploy.yml");

    assertThat(compose)
        .contains("keycloak-identity-admin-reconcile:")
        .contains("RAMALS_ADMIN_IDENTITY_CLIENT_SECRET: ${RAMALS_ADMIN_IDENTITY_CLIENT_SECRET:?")
        .contains("keycloak-identity-admin-reconcile: {condition: service_completed_successfully}");
  }

  @Test
  void localKubernetesBootstrapStoresReconcilesAndInjectsTheCredential() throws IOException {
    String bootstrap = readRepoFile("deploy/k8s/dev/bootstrap.ps1");
    String deployment = readRepoFile("deploy/k8s/dev/learning-platform.yaml");
    String reconcile = readRepoFile("infrastructure/docker/keycloak/reconcile-identity-admin.sh");

    assertThat(bootstrap)
        .contains("ramals-dev-identity-admin")
        .contains("RAMALS_ADMIN_IDENTITY_CLIENT_SECRET")
        .contains("reconcile-identity-admin.sh")
        .contains("rollout restart deployment/learning-platform");
    assertThat(deployment)
        .contains("name: ramals-dev-identity-admin")
        .contains("key: RAMALS_ADMIN_IDENTITY_CLIENT_SECRET")
        .contains("optional: true");
    assertThat(reconcile)
        .contains("--rolename manage-users")
        .contains("--rolename view-users")
        .contains("--rolename manage-realm")
        .contains("remove-roles")
        .contains("--rolename SERVICE");
  }

  private static String readRepoFile(String relative) throws IOException {
    Path fromModule = Path.of("..", relative);
    Path path = Files.exists(fromModule) ? fromModule : Path.of(relative);
    assertThat(Files.exists(path)).as("repository file %s", path.toAbsolutePath()).isTrue();
    return Files.readString(path, StandardCharsets.UTF_8);
  }
}
