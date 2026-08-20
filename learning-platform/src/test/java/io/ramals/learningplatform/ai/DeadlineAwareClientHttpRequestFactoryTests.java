package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** M1-T14 coverage for propagating caller deadlines into Java transport calls. */
class DeadlineAwareClientHttpRequestFactoryTests {

  private static final String PROPOSAL_JSON = """
      {
        "contractVersion": "1.0",
        "proposalId": "01920000-0000-7000-8000-0000000000c1",
        "agentType": "ASSESSMENT",
        "agentVersion": "ASSESSMENT_AGENT_V1",
        "promptVersion": "ASSESSMENT_PROMPT_V1",
        "modelRoute": "assessment-default",
        "trustLevel": "NON_AUTHORITATIVE",
        "confidence": "0.5",
        "reasonCodes": [],
        "proposal": {},
        "validation": null,
        "usage": null
        }
      """;

  @Test
  void callerBudgetClampsConnectAndReadTimeouts() throws IOException {
    DeadlineAwareClientHttpRequestFactory factory = configuredFactory();
    HttpURLConnection connection = openConnection();

    int[] timeouts = DeadlineAwareClientHttpRequestFactory.execute(100, () -> {
      try {
        factory.prepareConnection(connection, "POST");
        return new int[] {connection.getConnectTimeout(), connection.getReadTimeout()};
      } catch (IOException failure) {
        throw new RuntimeException(failure);
      }
    });

    assertThat(timeouts[0]).isBetween(1, 100);
    assertThat(timeouts[1]).isBetween(1, 100);
  }

  @Test
  void configuredTimeoutsRemainUnchangedOutsideADeadlineScope() throws IOException {
    DeadlineAwareClientHttpRequestFactory factory = configuredFactory();
    HttpURLConnection connection = openConnection();

    factory.prepareConnection(connection, "POST");

    assertThat(connection.getConnectTimeout()).isEqualTo(2_000);
    assertThat(connection.getReadTimeout()).isEqualTo(12_000);
  }

  @Test
  void anExhaustedScopeFailsBeforeStartingTheOperation() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(() -> DeadlineAwareClientHttpRequestFactory.execute(0, () -> {
      calls.incrementAndGet();
      return "not reached";
    }))
        .isInstanceOf(AiUnavailableException.class)
        .extracting(failure -> ((AiUnavailableException) failure).code())
        .isEqualTo("AI_DEADLINE_EXCEEDED");

    assertThat(calls).hasValue(0);
  }

  @Test
  void tutorTransportTimeoutIsNormalizedAsDeadlineExceeded() throws IOException {
    AtomicInteger requests = new AtomicInteger();
    ExecutorService executor = Executors.newCachedThreadPool();
    HttpServer server = slowServer(executor, "/", requests, 1_000, "{}");
    try {
      DeadlineAwareClientHttpRequestFactory factory = configuredFactory();
      RestClient restClient = RestClient.builder()
          .baseUrl(baseUrl(server))
          .requestFactory(factory)
          .build();
      RamalsAiTutorClient client = new RamalsAiTutorClient(
          restClient, new AiCallGuard(3, Duration.ofSeconds(30), 4, java.time.Instant::now), () -> "test-workload-token");

      assertThatThrownBy(() -> client.requestTutorResponse(request(), 150))
          .isInstanceOf(AiUnavailableException.class)
          .extracting(failure -> ((AiUnavailableException) failure).code())
          .isEqualTo("AI_DEADLINE_EXCEEDED");
      assertThat(requests).hasValue(1);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void assessmentTokenAndProposalShareOneCallerDeadline() throws IOException {
    AtomicInteger tokenRequests = new AtomicInteger();
    AtomicInteger aiRequests = new AtomicInteger();
    ExecutorService executor = Executors.newCachedThreadPool();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", exchange -> {
      tokenRequests.incrementAndGet();
      delayedResponse(exchange, 80, "{\"access_token\":\"workload-token\",\"expires_in\":60}");
    });
    server.createContext("/internal/v1/assessment/propose", exchange -> {
      aiRequests.incrementAndGet();
      delayedResponse(exchange, 250, PROPOSAL_JSON);
    });
    server.setExecutor(executor);
    server.start();
    try {
      DeadlineAwareClientHttpRequestFactory factory = configuredFactory();
      RestClient aiClient = RestClient.builder()
          .baseUrl(baseUrl(server))
          .requestFactory(factory)
          .build();
      RestClient tokenClient = RestClient.builder()
          .baseUrl(baseUrl(server) + "/token")
          .requestFactory(factory)
          .build();
      WorkloadTokenProvider tokenProvider =
          new WorkloadTokenProvider(tokenClient, "client-id", "client-secret", "ramals-ai");
      RamalsAiAssessmentClient client = new RamalsAiAssessmentClient(
          aiClient, new AiCallGuard(3, Duration.ofSeconds(30), 4, java.time.Instant::now),
          tokenProvider);

      assertThatThrownBy(() -> client.requestAssessmentProposal(request(), 300, "FOUNDATIONAL"))
          .isInstanceOf(AiUnavailableException.class)
          .extracting(failure -> ((AiUnavailableException) failure).code())
          .isEqualTo("AI_DEADLINE_EXCEEDED");
      assertThat(tokenRequests).hasValue(1);
      assertThat(aiRequests).hasValue(1);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static DeadlineAwareClientHttpRequestFactory configuredFactory() {
    DeadlineAwareClientHttpRequestFactory factory = new DeadlineAwareClientHttpRequestFactory();
    factory.setConnectTimeout(RamalsAiTutorClient.CONNECT_TIMEOUT);
    factory.setReadTimeout(RamalsAiTutorClient.READ_TIMEOUT);
    return factory;
  }

  private static HttpURLConnection openConnection() throws IOException {
    return (HttpURLConnection) URI.create("http://127.0.0.1/").toURL().openConnection();
  }

  private static HttpServer slowServer(
      ExecutorService executor, String path, AtomicInteger requests, long delayMillis, String body)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(path, exchange -> {
      requests.incrementAndGet();
      delayedResponse(exchange, delayMillis, body);
    });
    server.setExecutor(executor);
    server.start();
    return server;
  }

  private static void delayedResponse(HttpExchange exchange, long delayMillis, String body)
      throws IOException {
    try {
      Thread.sleep(delayMillis);
      byte[] response = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(response);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  private static String baseUrl(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION,
        "01920000-0000-7000-8000-0000000000a1",
        "01920000-0000-7000-8000-0000000000b1",
        new LearnerRef("ref-1", "en-IN"),
        new LearningContext("KAFKA_PARTITIONING", null, null, "NEEDS_PRACTICE", null),
        new DomainContext("KAFKA", DomainType.TECHNOLOGY, "v1"),
        null,
        new Constraints(InteractionClass.INTERACTIVE_AI, 12_000, null, null, null),
        "EXPLAIN");
  }
}
