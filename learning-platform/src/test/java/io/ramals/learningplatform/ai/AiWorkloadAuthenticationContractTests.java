package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Every AI client authenticates as the workload (M1-ADR-003).
 *
 * <p>Written because the platform shipped without it. The tutor and adaptation clients sent no
 * {@code Authorization} header while the AI plane required one on every agent route, so each call
 * returned 401 — which the clients correctly translate into "the AI plane could not be reached", and
 * the platform reported a healthy degradation while tutoring and adaptation were entirely off. No
 * test failed, because each side was correct and well tested in isolation and nothing checked that
 * they agreed.
 *
 * <p>The server here enforces the AI plane's actual rule: a request with no bearer token is refused
 * with 401 and {@code WORKLOAD_AUTHENTICATION_REQUIRED}, exactly as {@code require_workload_identity}
 * does in {@code ramals_ai/api/internal.py}. That rule is pinned on the Python side by
 * {@code test_every_agent_route_requires_workload_identity}; this is the other half of the same
 * contract, and the pair is what stops the two runtimes drifting apart again.
 */
class AiWorkloadAuthenticationContractTests {

  private static final String TOKEN = "test-workload-token";

  private record Captured(String path, String authorization) {}

  /** A stand-in for the AI plane that refuses an unauthenticated request the way the real one does. */
  private static HttpServer aiPlane(List<Captured> received) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    for (String path : List.of(
        "/internal/v1/tutor/respond",
        "/internal/v1/adaptation/propose",
        "/internal/v1/assessment/propose")) {
      server.createContext(path, exchange -> respond(exchange, received));
    }
    server.setExecutor(null);
    server.start();
    return server;
  }

  private static void respond(HttpExchange exchange, List<Captured> received) throws IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    received.add(new Captured(exchange.getRequestURI().getPath(), authorization));

    if (authorization == null || !authorization.startsWith("Bearer ")) {
      byte[] refusal = ("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
          + "\"code\":\"WORKLOAD_AUTHENTICATION_REQUIRED\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
      exchange.sendResponseHeaders(401, refusal.length);
      exchange.getResponseBody().write(refusal);
      exchange.close();
      return;
    }

    byte[] body = proposalJson().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static String proposalJson() {
    return """
        {"contractVersion":"1.0","proposalId":"01920000-0000-7000-8000-0000000000c1",
         "agentType":"TUTOR","agentVersion":"TUTOR_AGENT_V1","promptVersion":"TUTOR_PROMPT_V1",
         "modelRoute":"ci-fake","trustLevel":"NON_AUTHORITATIVE","proposal":{}}
        """;
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope(
        "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        new LearnerRef("opaque-learner-ref-001", "en-IN"),
        new LearningContext("KAFKA_TOPIC", null, null, "NEEDS_PRACTICE", null),
        null, null,
        new Constraints(InteractionClass.INTERACTIVE_AI, 8000, null, null, null), null);
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

  // -- every client presents a workload token -------------------------------------------------------

  @Test
  @DisplayName("the tutor client authenticates as the workload")
  void theTutorClientAuthenticates() throws IOException {
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received);
    try {
      new RamalsAiTutorClient(clientFor(server), guard(), () -> TOKEN)
          .requestTutorResponse(request(), 8_000);
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement()
        .satisfies(captured -> assertThat(captured.authorization()).isEqualTo("Bearer " + TOKEN));
  }

  @Test
  @DisplayName("the adaptation client authenticates as the workload")
  void theAdaptationClientAuthenticates() throws IOException {
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received);
    try {
      new RamalsAiAdaptationClient(clientFor(server), guard(), () -> TOKEN)
          .requestAdaptationProposal(request(), 8_000);
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement()
        .satisfies(captured -> assertThat(captured.authorization()).isEqualTo("Bearer " + TOKEN));
  }

  @Test
  @DisplayName("the assessment client authenticates as the workload")
  void theAssessmentClientAuthenticates() throws IOException {
    List<Captured> received = new CopyOnWriteArrayList<>();
    HttpServer server = aiPlane(received);
    try {
      new RamalsAiAssessmentClient(clientFor(server), guard(), () -> TOKEN)
          .requestAssessmentProposal(request(), 8_000, "FOUNDATIONAL");
    } finally {
      server.stop(0);
    }

    assertThat(received).singleElement()
        .satisfies(captured -> assertThat(captured.authorization()).isEqualTo("Bearer " + TOKEN));
  }

  // -- and the learner's own token is never the one presented ----------------------------------------

  @Test
  @DisplayName("no client can be built without something to authenticate with")
  void aClientCannotBeBuiltUnauthenticated() throws IOException {
    HttpServer server = aiPlane(new ArrayList<>());
    try {
      RestClient rest = clientFor(server);
      // Structural rather than behavioural. The regression this file exists for was a client
      // constructed without a token source; requiring one makes that state unrepresentable rather
      // than merely tested for.
      assertThat(catchNullPointer(() -> new RamalsAiTutorClient(rest, guard(), null))).isTrue();
      assertThat(catchNullPointer(() -> new RamalsAiAdaptationClient(rest, guard(), null))).isTrue();
      assertThat(catchNullPointer(() -> new RamalsAiAssessmentClient(rest, guard(), null))).isTrue();
    } finally {
      server.stop(0);
    }
  }

  private static boolean catchNullPointer(Runnable construction) {
    try {
      construction.run();
      return false;
    } catch (NullPointerException expected) {
      return true;
    }
  }
}
