package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Which failures are evidence about the AI plane's health, driven through the real transport.
 *
 * <p>The distinction this file defends cannot be seen from the error code. {@code
 * AI_DEADLINE_EXCEEDED} is produced in two opposite situations — the caller's budget expired before
 * anything was sent, and the dependency was contacted and did not answer in time — so classifying
 * from the code gets one of them wrong whichever way it is written. An earlier version excluded the
 * code globally, which meant a genuinely slow AI plane could no longer open the breaker: the exact
 * failure mode the guard's own documentation calls the more dangerous of the two.
 *
 * <p>So these tests go through {@link DeadlineAwareClientHttpRequestFactory} and a real socket
 * rather than throwing {@link AiUnavailableException} by hand. Whether a request was dispatched is
 * the fact the classification rests on, and only the real path establishes it.
 */
class AiCallFailureOriginTests {

  private static final int THRESHOLD = 3;

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope(
        "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        new LearnerRef("opaque-learner-ref-001", "en-IN"),
        new LearningContext("KAFKA_TOPIC", null, null, "NEEDS_PRACTICE", null),
        null, null,
        new Constraints(InteractionClass.INTERACTIVE_AI, 8000, null, null, null), null);
  }

  private static AiCallGuard guard() {
    return new AiCallGuard(THRESHOLD, Duration.ofSeconds(30), 4, Instant::now);
  }

  private static RamalsAiTutorClient tutorFor(String baseUrl, AiCallGuard guard) {
    DeadlineAwareClientHttpRequestFactory factory = DeadlineAwareClientHttpRequestFactory.forAiPlane();
    factory.setConnectTimeout(2_000);
    factory.setReadTimeout(5_000);
    RestClient rest =
        RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    return new RamalsAiTutorClient(rest, guard, () -> "test-workload-token");
  }

  /** A server that accepts the connection and then stalls well past any deadline used here. */
  private static HttpServer stallingServer() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/tutor/respond", exchange -> {
      try {
        Thread.sleep(3_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.setExecutor(Executors.newFixedThreadPool(THRESHOLD + 1));
    server.start();
    return server;
  }

  private static String urlOf(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Calls with a budget that is certain to be gone before any request is started.
   *
   * <p>A very small positive deadline is not good enough: whether the check bites before or after
   * the request object is created depends on scheduling, so the same call classifies either way on
   * different runs. Nesting inside an outer scope that has already elapsed is deterministic — the
   * earlier deadline wins, and it has passed.
   */
  private static AiUnavailableException callAfterTheBudgetIsGone(RamalsAiTutorClient client) {
    try {
      DeadlineAwareClientHttpRequestFactory.execute(30, () -> {
        try {
          Thread.sleep(60);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        return client.requestTutorResponse(request(), 8_000);
      });
      throw new AssertionError("the call was expected to fail");
    } catch (AiUnavailableException failure) {
      return failure;
    }
  }

  private static AiUnavailableException callAndCatch(
      RamalsAiTutorClient client, long deadlineMillis) {
    try {
      client.requestTutorResponse(request(), deadlineMillis);
      throw new AssertionError("the call was expected to fail");
    } catch (AiUnavailableException failure) {
      return failure;
    }
  }

  // -- the dependency was contacted: these must be able to open the breaker ------------------------

  @Test
  @DisplayName("a dispatched request that times out in flight opens the circuit")
  void anInFlightTimeoutOpensTheCircuit() throws IOException {
    HttpServer server = stallingServer();
    AiCallGuard guard = guard();
    try {
      RamalsAiTutorClient client = tutorFor(urlOf(server), guard);

      for (int attempt = 1; attempt <= THRESHOLD; attempt++) {
        AiUnavailableException failure = callAndCatch(client, 400);
        // Reported to the learner as a deadline, because that is what they experienced. Attributed
        // to the dependency, because it was asked and did not answer.
        assertThat(failure.code()).isEqualTo("AI_DEADLINE_EXCEEDED");
        assertThat(failure.origin()).isEqualTo(FailureOrigin.DEPENDENCY);
      }

      assertThat(guard.state())
          .as("a slow AI plane must still be escapable; this is what the breaker is for")
          .isEqualTo(AiCallGuard.State.OPEN);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("a refused connection opens the circuit")
  void aRefusedConnectionOpensTheCircuit() {
    AiCallGuard guard = guard();
    RamalsAiTutorClient client = tutorFor("http://127.0.0.1:1", guard);

    for (int attempt = 1; attempt <= THRESHOLD; attempt++) {
      AiUnavailableException failure = callAndCatch(client, 8_000);
      assertThat(failure.code()).isEqualTo("AI_TRANSPORT_FAILURE");
      assertThat(failure.origin()).isEqualTo(FailureOrigin.DEPENDENCY);
    }

    assertThat(guard.state()).isEqualTo(AiCallGuard.State.OPEN);
  }

  // -- nothing was dispatched: these must not ------------------------------------------------------

  @Test
  @DisplayName("a budget exhausted before dispatch does not open the circuit")
  void preDispatchExhaustionDoesNotOpenTheCircuit() throws IOException {
    HttpServer server = stallingServer();
    AiCallGuard guard = guard();
    try {
      RamalsAiTutorClient client = tutorFor(urlOf(server), guard);

      // The AI plane is never asked, so its health is unchanged however many callers arrive late.
      for (int attempt = 1; attempt <= THRESHOLD + 2; attempt++) {
        AiUnavailableException failure = callAfterTheBudgetIsGone(client);
        assertThat(failure.code()).isEqualTo("AI_DEADLINE_EXCEEDED");
        assertThat(failure.origin()).isEqualTo(FailureOrigin.CALLER);
      }

      assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("a non-positive deadline is refused as a caller failure")
  void aNonPositiveDeadlineIsACallerFailure() {
    AiCallGuard guard = guard();
    RamalsAiTutorClient client = tutorFor("http://127.0.0.1:1", guard);

    for (int attempt = 1; attempt <= THRESHOLD + 2; attempt++) {
      assertThat(callAndCatch(client, 0).origin()).isEqualTo(FailureOrigin.CALLER);
    }

    assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);
  }

  @Test
  @DisplayName("a guard refusal is not evidence about the dependency")
  void aGuardRefusalIsNotEvidence() {
    AiCallGuard guard = new AiCallGuard(THRESHOLD, Duration.ofSeconds(30), 1, Instant::now);

    // Hold the only permit, so the nested call is refused by the bulkhead rather than attempted.
    guard.call(() -> {
      for (int attempt = 1; attempt <= THRESHOLD + 2; attempt++) {
        assertThatThrownBy(() -> guard.call(() -> "never runs"))
            .isInstanceOf(AiCallGuard.GuardRefusal.class)
            .satisfies(refusal ->
                assertThat(((AiUnavailableException) refusal).origin())
                    .isEqualTo(FailureOrigin.GUARD));
      }
      return "held";
    });

    assertThat(guard.state())
        .as("a busy service must not be turned into an unavailable one by its own bulkhead")
        .isEqualTo(AiCallGuard.State.CLOSED);
  }

  // -- and the two must not cancel each other out ----------------------------------------------------

  @Test
  @DisplayName("caller failures do not reset accumulated dependency failures")
  void callerFailuresDoNotResetDependencyFailures() throws IOException {
    HttpServer server = stallingServer();
    AiCallGuard guard = guard();
    try {
      RamalsAiTutorClient client = tutorFor(urlOf(server), guard);

      // Two real timeouts, a late caller in between, then a third timeout. If ignoring the caller
      // also cleared the counter, a dependency failing between short-deadline calls would never
      // reach the threshold and the breaker would stay shut on a sick service.
      assertThat(callAndCatch(client, 400).origin()).isEqualTo(FailureOrigin.DEPENDENCY);
      assertThat(callAndCatch(client, 400).origin()).isEqualTo(FailureOrigin.DEPENDENCY);
      assertThat(callAfterTheBudgetIsGone(client).origin()).isEqualTo(FailureOrigin.CALLER);
      assertThat(guard.state()).isEqualTo(AiCallGuard.State.CLOSED);

      assertThat(callAndCatch(client, 400).origin()).isEqualTo(FailureOrigin.DEPENDENCY);

      assertThat(guard.state()).isEqualTo(AiCallGuard.State.OPEN);
    } finally {
      server.stop(0);
    }
  }
}
