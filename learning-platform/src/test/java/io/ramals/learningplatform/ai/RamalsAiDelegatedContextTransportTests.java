package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DiagnosticDispatchAuthorization;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.mcp.McpDelegatedContextTransportExtractor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * MCP-3.1 (M2-ADR-031): the outbound Java→ramals-ai transport hop.
 *
 * <p>Proves the delegated learner-context credential travels only as {@code
 * X-Ramals-Delegated-Context} -- present alongside, never instead of, the workload {@code
 * Authorization} header, absent entirely from the request body, and simply absent from the request
 * altogether when no context was minted for the call.
 */
class RamalsAiDelegatedContextTransportTests {

  private static final String TOKEN = "test-workload-token";
  private static final String DELEGATED_TOKEN = "test-delegated-context-jwt-not-real";
  private static final String DELEGATED_HEADER = McpDelegatedContextTransportExtractor.HEADER_NAME;

  private record Captured(String authorization, String delegatedContext, String body) {}

  private static HttpServer aiPlane(List<Captured> received, String path) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(path, exchange -> respond(exchange, received));
    server.setExecutor(null);
    server.start();
    return server;
  }

  private static void respond(HttpExchange exchange, List<Captured> received) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    exchange.getRequestBody().transferTo(body);
    received.add(new Captured(
        exchange.getRequestHeaders().getFirst("Authorization"),
        exchange.getRequestHeaders().getFirst(DELEGATED_HEADER),
        body.toString(StandardCharsets.UTF_8)));

    byte[] response = ("{\"contractVersion\":\"1.0\",\"proposalId\":\"p-1\","
        + "\"agentType\":\"ADAPTATION\",\"agentVersion\":\"v1\",\"promptVersion\":\"v1\","
        + "\"modelRoute\":\"ci-fake\",\"trustLevel\":\"NON_AUTHORITATIVE\",\"proposal\":{}}")
        .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private static AiCallGuard guard() {
    return new AiCallGuard(3, Duration.ofSeconds(30), 4, Instant::now);
  }

  private static RestClient clientFor(HttpServer server) {
    DeadlineAwareClientHttpRequestFactory factory = DeadlineAwareClientHttpRequestFactory.forAiPlane();
    factory.setConnectTimeout(2_000);
    factory.setReadTimeout(5_000);
    return RestClient.builder()
        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
        .requestFactory(factory)
        .build();
  }

  private static AiRequestEnvelope adaptationRequest() {
    return new AiRequestEnvelope(
        "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        new LearnerRef("opaque-learner-ref-001", "en-IN"),
        new LearningContext("KAFKA_TOPIC", null, null, "NEEDS_PRACTICE", null),
        null, null,
        new Constraints(InteractionClass.INTERACTIVE_AI, 8000, null, null, null), null);
  }

  private static DelegatedAiExecutionContext minted() {
    return new DelegatedAiExecutionContext(Optional.of(DELEGATED_TOKEN));
  }

  // -- adaptation client -----------------------------------------------------------------------------

  @Test
  void adaptationClientAttachesTheDelegatedHeaderAlongsideTheWorkloadAuthorization()
      throws IOException {
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received, "/internal/v1/adaptation/propose");
    try {
      new RamalsAiAdaptationClient(clientFor(server), guard(), () -> TOKEN)
          .requestAdaptationProposal(adaptationRequest(), 8_000, minted());
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement().satisfies(captured -> {
      assertThat(captured.authorization()).isEqualTo("Bearer " + TOKEN);
      assertThat(captured.delegatedContext()).isEqualTo(DELEGATED_TOKEN);
      assertThat(captured.body()).doesNotContain(DELEGATED_TOKEN);
    });
  }

  @Test
  void adaptationClientSendsNoDelegatedHeaderWhenNoContextWasMinted() throws IOException {
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received, "/internal/v1/adaptation/propose");
    try {
      new RamalsAiAdaptationClient(clientFor(server), guard(), () -> TOKEN)
          .requestAdaptationProposal(adaptationRequest(), 8_000, DelegatedAiExecutionContext.NONE);
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement().satisfies(captured -> {
      assertThat(captured.authorization()).isEqualTo("Bearer " + TOKEN);
      assertThat(captured.delegatedContext()).isNull();
    });
  }

  @Test
  void adaptationClientTwoArgumentOverloadAlsoSendsNoDelegatedHeader() throws IOException {
    // The pre-MCP-3.1 call shape every existing caller/test still uses.
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received, "/internal/v1/adaptation/propose");
    try {
      new RamalsAiAdaptationClient(clientFor(server), guard(), () -> TOKEN)
          .requestAdaptationProposal(adaptationRequest(), 8_000);
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement()
        .satisfies(captured -> assertThat(captured.delegatedContext()).isNull());
  }

  // -- diagnostic assessment client -------------------------------------------------------------------

  private static io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest
      diagnosticAssessmentRequest() {
    Instant asOf = Instant.parse("2026-08-27T10:00:00Z");
    return new io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest(
        "1.0", UUID.randomUUID().toString(), "wf-diag-1",
        new Constraints(InteractionClass.INTERACTIVE_AI, 8_000, null, null, null),
        new GroundedContext("1.0", "ctx-1", "opaque-learner-ref-001", asOf,
            asOf.plusSeconds(300), "GROUNDING_RETRIEVAL_V1", List.of()));
  }

  @Test
  void diagnosticAssessmentClientAttachesTheDelegatedHeaderAlongsideDispatchAuthorization()
      throws IOException {
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received, "/internal/v1/diagnostic-assessment/propose");
    try {
      new RamalsAiDiagnosticAssessmentClient(clientFor(server), guard(), () -> TOKEN)
          .requestDiagnosticAssessment(
              diagnosticAssessmentRequest(), new DiagnosticDispatchAuthorization(7, "a".repeat(64)),
              8_000, minted());
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement().satisfies(captured -> {
      assertThat(captured.authorization()).isEqualTo("Bearer " + TOKEN);
      assertThat(captured.delegatedContext()).isEqualTo(DELEGATED_TOKEN);
      assertThat(captured.body()).doesNotContain(DELEGATED_TOKEN);
    });
  }

  @Test
  void diagnosticAssessmentClientThreeArgumentOverloadAlsoSendsNoDelegatedHeader()
      throws IOException {
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received, "/internal/v1/diagnostic-assessment/propose");
    try {
      new RamalsAiDiagnosticAssessmentClient(clientFor(server), guard(), () -> TOKEN)
          .requestDiagnosticAssessment(
              diagnosticAssessmentRequest(), new DiagnosticDispatchAuthorization(7, "a".repeat(64)),
              8_000);
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement()
        .satisfies(captured -> assertThat(captured.delegatedContext()).isNull());
  }
}
