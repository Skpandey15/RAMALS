package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The AI-plane breaker must only ever react to the AI plane.
 *
 * <p>Assessment is the one call that contacts two services under one budget: it acquires a workload
 * token from the identity provider and then posts to the AI plane, deliberately inside the same
 * deadline scope so a slow identity provider cannot silently extend the model budget.
 *
 * <p>That creates a way to blame the wrong service. If "a request was dispatched" is recorded
 * without recording *which* service it went to, a token request that consumes the whole budget makes
 * the subsequent deadline look like the AI plane failing to answer — while the AI plane was never
 * contacted at all. Three of those would open its circuit and disable assessment generation because
 * Keycloak was slow.
 */
class AssessmentIdentityAttributionTests {

  private static final int THRESHOLD = 3;

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope(
        "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        new LearnerRef("opaque-learner-ref-001", "en-IN"),
        new LearningContext("KAFKA_TOPIC", null, null, "NEEDS_PRACTICE", null),
        null, null,
        new Constraints(InteractionClass.ASSESSMENT_PROPOSAL, 10_000, null, null, null), null);
  }

  /** Counts what each service actually received, so "never contacted" is observed, not assumed. */
  private record Endpoints(HttpServer server, AtomicInteger tokenHits, AtomicInteger aiHits) {}

  private static Endpoints endpoints(int tokenDelayMillis, int aiDelayMillis) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger tokenHits = new AtomicInteger();
    AtomicInteger aiHits = new AtomicInteger();

    server.createContext("/token", exchange -> {
      tokenHits.incrementAndGet();
      sleep(tokenDelayMillis);
      byte[] body = "{\"access_token\":\"issued-token\",\"expires_in\":300}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });

    server.createContext("/internal/v1/assessment/propose", exchange -> {
      aiHits.incrementAndGet();
      sleep(aiDelayMillis);
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });

    server.setExecutor(Executors.newFixedThreadPool(THRESHOLD + 2));
    server.start();
    return new Endpoints(server, tokenHits, aiHits);
  }

  private static void sleep(int millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static String urlOf(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Wires an assessment client the way {@code AiClientConfiguration} does, including a real
   * {@link WorkloadTokenProvider} over the deadline-aware transport.
   */
  private static RamalsAiAssessmentClient assessmentClient(HttpServer server, AiCallGuard guard) {
    RestClient aiClient = RestClient.builder()
        .baseUrl(urlOf(server))
        .requestFactory(DeadlineAwareClientHttpRequestFactory.forAiPlane())
        .build();
    RestClient tokenClient = RestClient.builder()
        .baseUrl(urlOf(server) + "/token")
        .requestFactory(DeadlineAwareClientHttpRequestFactory.forSupportingCall())
        .build();
    return new RamalsAiAssessmentClient(aiClient, guard,
        new WorkloadTokenProvider(tokenClient, "test-client", "test-secret", "ramals-ai"));
  }

  private static AiUnavailableException callAndCatch(
      RamalsAiAssessmentClient client, long deadlineMillis) {
    try {
      client.requestAssessmentProposal(request(), deadlineMillis, "FOUNDATIONAL");
      throw new AssertionError("the call was expected to fail");
    } catch (AiUnavailableException failure) {
      return failure;
    }
  }

  // -- a slow identity provider is not evidence about the AI plane ---------------------------------

  @Test
  @DisplayName("a token request that eats the budget does not blame the AI plane")
  void aSlowIdentityProviderDoesNotOpenTheAiCircuit() throws IOException {
    // The token endpoint alone outlasts the whole budget, so the AI request is never started.
    Endpoints endpoints = endpoints(600, 0);
    AiCallGuard guard = new AiCallGuard(THRESHOLD, Duration.ofSeconds(30), 4, Instant::now);
    try {
      RamalsAiAssessmentClient client = assessmentClient(endpoints.server(), guard);

      for (int attempt = 1; attempt <= THRESHOLD + 2; attempt++) {
        AiUnavailableException failure = callAndCatch(client, 250);
        // Named for the service that actually failed. The budget was spent on the token endpoint,
        // and reporting that as an AI deadline sent operators to inspect a healthy AI plane.
        assertThat(failure.code()).isEqualTo("AI_IDENTITY_FAILURE");
        assertThat(failure.origin())
            .as("the AI plane was never asked, so this says nothing about its health")
            .isEqualTo(FailureOrigin.CALLER);
      }

      // Observed, not assumed: the identity provider was contacted and the AI plane was not.
      assertThat(endpoints.tokenHits()).hasPositiveValue();
      assertThat(endpoints.aiHits())
          .as("the AI plane must have received nothing")
          .hasValue(0);
      assertThat(guard.state())
          .as("a slow identity provider must not disable assessment generation")
          .isEqualTo(AiCallGuard.State.CLOSED);
    } finally {
      endpoints.server().stop(0);
    }
  }

  // -- and the AI plane failing still counts ---------------------------------------------------------

  @Test
  @DisplayName("a token that succeeds and an AI plane that stalls opens the circuit")
  void aStallingAiPlaneStillOpensTheCircuit() throws IOException {
    // Token is fast, so the budget survives to reach the AI plane; the AI plane then stalls.
    Endpoints endpoints = endpoints(0, 3_000);
    AiCallGuard guard = new AiCallGuard(THRESHOLD, Duration.ofSeconds(30), 4, Instant::now);
    try {
      RamalsAiAssessmentClient client = assessmentClient(endpoints.server(), guard);

      for (int attempt = 1; attempt <= THRESHOLD; attempt++) {
        AiUnavailableException failure = callAndCatch(client, 500);
        assertThat(failure.origin())
            .as("the AI plane was contacted and did not answer")
            .isEqualTo(FailureOrigin.DEPENDENCY);
      }

      assertThat(endpoints.aiHits())
          .as("the AI plane must actually have been reached")
          .hasPositiveValue();
      assertThat(guard.state()).isEqualTo(AiCallGuard.State.OPEN);
    } finally {
      endpoints.server().stop(0);
    }
  }
}
