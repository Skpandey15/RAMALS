package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deployed backend must emit logs an interactionId can actually be found in.
 *
 * <p>Correlation is not a property of the code alone. The identifiers reach MDC on every request,
 * but MDC only becomes visible if the active profile selects the structured encoder. Running the
 * shared stack without a profile produced a backend whose logs carried the trace id and dropped the
 * interactionId, which makes the documented diagnosis procedure -- take the support code from the
 * error screen, search the logs -- return nothing at all.
 *
 * <p>This asserts against the deployment topology file rather than a Spring context, because the
 * defect lived in the topology and no amount of application testing would have seen it.
 */
class DeploymentLoggingTests {

  /** Profiles that select the structured encoder; keep in step with the application-*.yml set. */
  private static final Set<String> STRUCTURED_PROFILES = Set.of("shared", "prod");

  private static String composeTopology() throws IOException {
    Path topology = Path.of("..", "deploy", "compose.deploy.yml");
    assertThat(topology).as("deployment topology").exists();
    return Files.readString(topology, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("the deployed backend activates a profile that emits structured logs")
  void deployedBackendActivatesAStructuredLoggingProfile() throws IOException {
    String topology = composeTopology();

    assertThat(topology)
        .as("SPRING_PROFILES_ACTIVE must be set, or the default profile's console pattern wins")
        .contains("SPRING_PROFILES_ACTIVE");

    String defaulted = defaultProfileFrom(topology);
    assertThat(STRUCTURED_PROFILES)
        .as(
            "the default for RAMALS_SPRING_PROFILES is what the shared stack actually runs; "
                + "'%s' does not select the structured encoder",
            defaulted)
        .contains(defaulted);
  }

  @Test
  @DisplayName("every profile the deployment can select defines the structured encoder")
  void eachStructuredProfileDefinesTheEncoder() throws IOException {
    for (String profile : STRUCTURED_PROFILES) {
      Path configuration =
          Path.of("src", "main", "resources", "application-" + profile + ".yml");
      assertThat(configuration).as("configuration for profile %s", profile).exists();
      assertThat(Files.readString(configuration, StandardCharsets.UTF_8))
          .as("profile %s must select a structured console encoder", profile)
          .contains("logstash");
    }
  }

  /**
   * Extracts the fallback in {@code ${RAMALS_SPRING_PROFILES:-shared}}. An operator who sets the
   * variable takes responsibility for it; the fallback is what runs when nobody does, so that is
   * the value worth asserting.
   */
  private static String defaultProfileFrom(String topology) {
    int marker = topology.indexOf("SPRING_PROFILES_ACTIVE");
    String line = topology.substring(marker, topology.indexOf('\n', marker));
    int fallback = line.indexOf(":-");
    assertThat(fallback)
        .as("SPRING_PROFILES_ACTIVE should carry a default so an unset variable is not silent")
        .isGreaterThan(-1);
    return line.substring(fallback + 2, line.indexOf('}', fallback)).trim();
  }
}
