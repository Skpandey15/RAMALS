package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structural verification of the build-once/promote-the-same-artifact pipeline. The behavioural
 * checks (trust boundary, release-hold state machine) run as CI scripts; these assertions pin the
 * pipeline's shape so a later edit cannot quietly drop immutability, scanning, or provenance.
 */
class ReleasePipelineTests {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void releaseWorkflowPublishesOnlyFromTrustedRefs() throws IOException {
    String release = read(".github/workflows/release.yml");

    // Trusted triggers only: never reachable from a pull request.
    assertThat(release).contains("branches: [main]").contains("tags: ['v*']");
    assertThat(release).doesNotContain("pull_request");

    // Immutable identity, scanning, SBOM, and provenance are all part of publishing.
    assertThat(release)
        .contains("type=sha")
        .contains("aquasec/trivy")
        .contains("sbom-action")
        .contains("attest-build-provenance")
        .contains("packages: write");
  }

  @Test
  void pullRequestWorkflowCarriesNoPublishOrDeployCapability() throws IOException {
    String pr = read(".github/workflows/pr-ci.yml");

    assertThat(pr).contains("pull_request");
    assertThat(pr).contains("permissions:\n  contents: read");
    // The untrusted path must hold no publish capability whatsoever.
    assertThat(pr)
        .doesNotContain("packages: write")
        .doesNotContain("docker/login-action")
        .doesNotContain("docker/build-push-action");
  }

  @Test
  void scheduledRescanRevalidatesPublishedImagesWithoutRebuilding() throws IOException {
    String rescan = read(".github/workflows/scheduled-rescan.yml");

    assertThat(rescan).contains("schedule:").contains("cron:").contains("aquasec/trivy");
    // Re-scan must not rebuild or republish; it revalidates the deployed digest.
    assertThat(rescan).doesNotContain("build-push-action");
    assertThat(rescan).contains("desired-version.json");
  }

  @Test
  void desiredVersionManifestPinsImmutableDigests() throws IOException {
    JsonNode manifest = MAPPER.readTree(read("deploy/desired-version.json"));
    JsonNode components = manifest.get("components");

    assertThat(components.has("learning-platform")).isTrue();
    assertThat(components.has("web-ui")).isTrue();
    for (JsonNode component : components) {
      String digest = component.get("digest").asString();
      assertThat(digest).startsWith("sha256:");
      String image = component.get("image").asString();
      assertThat(image).doesNotContain(":latest");
    }
    assertThat(manifest.get("release").has("commit")).isTrue();
  }

  @Test
  void deploymentControllerImplementsRollbackAndReleaseHold() throws IOException {
    String controller = read("deploy/deploy-controller.sh");

    assertThat(controller)
        .contains("RELEASE_HELD")
        .contains("held_versions")
        .contains("known_good")
        // A health-gate failure must not be retried; only transient pull failures are.
        .contains("MAX_TRANSIENT_RETRIES")
        // Mutable tags are never deployable.
        .contains("sha256:");
  }

  private static String read(String relative) throws IOException {
    return Files.readString(repoFile(relative));
  }

  private static Path repoFile(String relative) {
    Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (Path base = current; base != null; base = base.getParent()) {
      Path candidate = base.resolve(relative);
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not locate " + relative + " from " + current);
  }
}
