package io.ramals.learningplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Structural checks for the shipped observability assets: the Grafana dashboard and the runbook. */
class ObservabilityAssetsTests {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void grafanaDashboardIsValidAndCoversTheKeyOperations() throws IOException {
    JsonNode dashboard = MAPPER.readTree(
        Files.readString(repoFile("infrastructure/observability/grafana/ramals-mvp0-dashboard.json")));

    assertThat(dashboard.get("title").asString()).isEqualTo("RAMALS MVP-0");
    assertThat(dashboard.get("panels")).isNotEmpty();

    String queries = dashboard.toString();
    assertThat(queries)
        .contains("http_server_requests")
        .contains("ramals_api_errors")
        .contains("status=\\\"409\\\"")   // optimistic-retry conflicts
        .contains("RATE_LIMITED");        // rate-limit rejections
  }

  @Test
  void investigationRunbookExplainsTheCorrelationChainAndRedaction() throws IOException {
    String runbook = Files.readString(repoFile("docs/architecture/observability-runbook.md"));

    assertThat(runbook)
        .contains("interactionId")
        .contains("traceId")
        .contains("spanId")
        .contains("Support code")
        .contains("RAMALS_TRACE_SAMPLE")
        .contains("Redaction");
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
