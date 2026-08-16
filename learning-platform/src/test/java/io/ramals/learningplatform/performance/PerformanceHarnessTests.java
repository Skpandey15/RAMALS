package io.ramals.learningplatform.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structural verification that the performance harness is complete and well-formed. A full load run
 * needs a live platform and Keycloak, so CI verifies the harness declares the required executor
 * semantics, thresholds, machine-readable baseline, DB benchmarks, and reproducibility framing.
 */
class PerformanceHarnessTests {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void baselineSchemaAndExampleCaptureStableEnvironmentMetadataAndPercentiles() throws IOException {
    JsonNode schema = MAPPER.readTree(read("performance/baselines/baseline.schema.json"));
    JsonNode required = schema.get("required");
    assertThat(required.toString())
        .contains("environment").contains("commit").contains("dataset_version")
        .contains("script_version").contains("executor_model").contains("latency_ms");

    JsonNode example = MAPPER.readTree(read("performance/baselines/baseline.example.json"));
    assertThat(example.get("commit").asString()).isNotBlank();
    assertThat(example.get("environment").asString()).isNotBlank();
    JsonNode latency = example.get("latency_ms");
    assertThat(latency.has("p50")).isTrue();
    assertThat(latency.has("p95")).isTrue();
    assertThat(latency.has("p99")).isTrue();
  }

  @Test
  void thresholdsDefineRequestClassBudgetsAndAdaptiveDecisionLatency() throws IOException {
    JsonNode thresholds = MAPPER.readTree(read("performance/thresholds/mvp0.json"));
    assertThat(thresholds.get("request_class_p95_ms").has("skill_map_read")).isTrue();
    assertThat(thresholds.get("request_class_p95_ms").has("diagnostic")).isTrue();
    JsonNode adl = thresholds.get("adaptive_decision_latency_ms");
    assertThat(adl.has("p50")).isTrue();
    assertThat(adl.has("p95")).isTrue();
    assertThat(adl.has("p99")).isTrue();
  }

  @Test
  void scenariosDeclareExplicitOpenAndClosedExecutors() throws IOException {
    String diagnostic = read("performance/scenarios/diagnostic.js");
    assertThat(diagnostic)
        .contains("constant-arrival-rate")          // open model
        .contains("adaptive_decision_latency")       // ADL measurement
        .contains("thresholds");

    assertThat(read("performance/scenarios/mixed-learning.js"))
        .contains("ramping-arrival-rate")
        .contains("request_class");

    assertThat(read("performance/scenarios/auth.js"))
        .contains("jwt_validated_request")           // JWT/JWKS overhead
        .contains("unauthenticated_request");

    assertThat(read("performance/scenarios/concurrency-idempotency.js"))
        .contains("per-vu-iterations")               // closed model, labeled
        .contains("Idempotency-Key");
  }

  @Test
  void databaseBenchmarksExplainTheHotPathQueries() throws IOException {
    String sql = read("performance/db/explain-analyze.sql");
    assertThat(sql)
        .contains("EXPLAIN (ANALYZE, BUFFERS)")
        .contains("ledger.mastery_snapshot")
        .contains("ledger.evidence")
        .contains("core.assessment_attempt");
  }

  @Test
  void readmeFramesAReproducibleBaselineNotAnSla() throws IOException {
    String readme = read("performance/README.md");
    assertThat(readme)
        .contains("Adaptive Decision Latency")
        .contains("open")
        .contains("closed")
        .contains("not an unqualified SLA");
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
