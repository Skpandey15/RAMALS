package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.UuidV7;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a failed submission is classified, across a real HTTP boundary (residual **S2**).
 *
 * <p>The invariant these defend: <strong>only a provably safe refusal may become {@code FAILED}.</strong>
 * Everything else must reach the durable ambiguous path, because {@code FAILED} is a claim that no
 * provider execution exists, and making that claim wrongly leaves a batch running and billing under
 * a name RAMALS never learned — and licenses a resubmission that duplicates it.
 *
 * <p>The client previously read a status code and concluded from it. That cannot work: the same 500
 * covers "the SDK was missing" and "the connection dropped after create was sent", which are
 * opposite answers. The classification now requires the AI plane to say <em>explicitly</em> that
 * nothing was created, and treats every other response as ambiguous.
 *
 * <p>A real {@link HttpServer} rather than a mock, for the same reason the boundary tests use one:
 * the behaviour under test is how a response body is read, and a mock above that layer would assert
 * the mapping without ever exercising it.
 */
class ContractBSubmissionOutcomeTests {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<Responder> responder = new AtomicReference<>();
  private final AtomicInteger submissions = new AtomicInteger();

  @FunctionalInterface
  private interface Responder {
    void respond(HttpExchange exchange) throws IOException;
  }

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      submissions.incrementAndGet();
      responder.get().respond(exchange);
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /** The AI plane's shape for a failure it can prove created nothing. */
  private void answersNotCreated(int status, String code) {
    responder.set(exchange -> send(exchange, status,
        "{\"detail\":{\"code\":\"" + code + "\",\"detail\":\"refused\","
            + "\"submission\":\"NOT_CREATED\"}}"));
  }

  /** The AI plane's shape for a failure that cannot rule creation out. */
  private void answersMayExist(int status, String code) {
    responder.set(exchange -> send(exchange, status,
        "{\"detail\":{\"code\":\"" + code + "\",\"detail\":\"failed\","
            + "\"submission\":\"MAY_EXIST\"}}"));
  }

  private DurableExecutionPort port() {
    return new ContractBConfiguration().durableExecutionPort(baseUrl, () -> "token");
  }

  private DurableSubmissionAck submit() {
    try (CorrelationContext.Scope ignored = CorrelationContext.withCorrelation(
        UuidV7.generate().toString(), "0af7651916cd43dd8448eb211c80319c")) {
      return port().submit(new DurableSubmissionCommand(
          "req-s2", "idem-req-s2",
          "0000000000000000000000000000000000000000000000000000000000000001",
          "claude-haiku-4-5-20251001", 64,
          List.of(new DurableSubmissionCommand.Turn("user", "hello"))));
    }
  }

  // ================================================================================================
  // Definite refusal — the only outcome that may become FAILED
  // ================================================================================================

  @Test
  @DisplayName("an explicit NOT_CREATED is a definite refusal")
  void explicitNotCreatedIsARefusal() {
    answersNotCreated(502, "PROVIDER_INVALID_REQUEST");

    assertThatThrownBy(this::submit).isInstanceOf(DurableExecutionRefusedException.class);
  }

  @Test
  @DisplayName("every refusal the AI plane can prove is honoured as one")
  void everyProvableRefusalIsHonoured() {
    // The plane marks these NOT_CREATED because each was decided by something that read the request
    // and said no -- our capability gate, our governance ceilings, or the provider answering.
    record Case(int status, String code) {}
    for (Case refusal : List.of(
        new Case(503, "CONTRACT_B_UNSUPPORTED"),
        new Case(503, "ROUTE_NOT_CONFIGURED"),
        new Case(502, "PROVIDER_INVALID_REQUEST"),
        new Case(502, "PROVIDER_AUTH_ERROR"),
        new Case(429, "PROVIDER_RATE_LIMITED"),
        new Case(403, "COST_CEILING_EXCEEDED"))) {
      answersNotCreated(refusal.status(), refusal.code());

      assertThatThrownBy(this::submit)
          .as("%s must be a definite refusal", refusal.code())
          .isInstanceOf(DurableExecutionRefusedException.class);
    }
  }

  // ================================================================================================
  // Ambiguous — everything else
  // ================================================================================================

  @Test
  @DisplayName("NEGATIVE CONTROL: a 5xx after create may exist and must never be FAILED")
  void aFiveHundredAfterCreateIsNeverRetryable() {
    // The S2 defect exactly. The batch was created; the response then failed. Before the fix this
    // status alone made the outcome a definite refusal, the lifecycle recorded FAILED, and RAMALS
    // asserted that no provider execution existed while one was running and billing.
    answersMayExist(502, "SUBMISSION_UNCLASSIFIED");

    assertThatThrownBy(this::submit)
        .as("a failure that cannot rule out creation must never be a definite refusal")
        .isInstanceOf(DurableSubmissionAmbiguousException.class)
        .isNotInstanceOf(DurableExecutionRefusedException.class);
  }

  @Test
  @DisplayName("a timeout and an unavailable provider cannot rule creation out")
  void transportFailuresAreAmbiguous() {
    for (String code : List.of("PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE")) {
      answersMayExist(504, code);

      assertThatThrownBy(this::submit)
          .as("%s is the absence of an answer, never an answer", code)
          .isInstanceOf(DurableSubmissionAmbiguousException.class);
    }
  }

  @Test
  @DisplayName("a response with no marker at all is ambiguous, not a refusal")
  void anUnmarkedFailureIsAmbiguous() {
    // A proxy error page, an old AI plane, a gateway that never reached the service. The marker is
    // a positive claim; its absence is not evidence of anything.
    responder.set(exchange -> send(exchange, 500, "{\"detail\":\"Internal Server Error\"}"));

    assertThatThrownBy(this::submit).isInstanceOf(DurableSubmissionAmbiguousException.class);
  }

  @Test
  @DisplayName("an unparseable body is ambiguous rather than throwing while classifying")
  void anUnparseableBodyIsAmbiguous() {
    responder.set(exchange -> send(exchange, 500, "<html>502 Bad Gateway</html>"));

    // A classifier that could fail while classifying a failure would decide the outcome by
    // accident. Unreadable means "not stated", which is the safe answer.
    assertThatThrownBy(this::submit).isInstanceOf(DurableSubmissionAmbiguousException.class);
  }

  @Test
  @DisplayName("a marker that is not NOT_CREATED is ambiguous")
  void onlyTheExactMarkerCounts() {
    for (String marker : List.of("MAY_EXIST", "not_created", "CREATED", "")) {
      responder.set(exchange -> send(exchange, 502,
          "{\"detail\":{\"code\":\"X\",\"submission\":\"" + marker + "\"}}"));

      assertThatThrownBy(this::submit)
          .as("marker %s must not be read as a refusal", marker)
          .isInstanceOf(DurableSubmissionAmbiguousException.class);
    }
  }

  @Test
  @DisplayName("a 429 without the marker is ambiguous, even though a provider 429 creates nothing")
  void aBareRateLimitIsAmbiguous() {
    // A 429 from the provider rules creation out and the plane says so. A 429 from an intermediary
    // says nothing about the provider at all, and the two are indistinguishable by status.
    responder.set(exchange -> send(exchange, 429, "{\"detail\":\"Too Many Requests\"}"));

    assertThatThrownBy(this::submit).isInstanceOf(DurableSubmissionAmbiguousException.class);
  }

  // ================================================================================================
  // Acceptance and partial acknowledgement
  // ================================================================================================

  @Test
  @DisplayName("an acknowledgement carrying an identity is usable")
  void anAcknowledgementWithAnIdentityIsUsable() {
    responder.set(exchange -> send(exchange, 201,
        "{\"provider_execution_id\":\"msgbatch_s2000001\",\"state\":\"RUNNING\","
            + "\"custom_id\":\"idem-req-s2\"}"));

    DurableSubmissionAck ack = submit();

    assertThat(ack.usable()).isTrue();
    assertThat(ack.providerExecutionId()).isEqualTo("msgbatch_s2000001");
  }

  @Test
  @DisplayName("a 2xx with no identity is not usable, and is not a refusal either")
  void aPartialAcknowledgementIsUnusable() {
    responder.set(exchange -> send(exchange, 201, "{\"state\":\"RUNNING\"}"));

    // The lifecycle turns this into SUBMIT_ACK_UNUSABLE / INDETERMINATE. What matters here is that
    // the client does not mistake it for a refusal: a batch may exist and simply not be named.
    DurableSubmissionAck ack = submit();

    assertThat(ack.usable()).isFalse();
  }

  @Test
  @DisplayName("a 2xx with no body at all is ambiguous")
  void anEmptyAcknowledgementIsAmbiguous() {
    responder.set(exchange -> {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });

    assertThatThrownBy(this::submit).isInstanceOf(DurableSubmissionAmbiguousException.class);
  }

  // ================================================================================================
  // The invariant that outranks all of the above
  // ================================================================================================

  @Test
  @DisplayName("no classification path ever submits more than once")
  void classificationNeverResubmits() {
    for (Runnable scenario : List.<Runnable>of(
        () -> answersNotCreated(502, "PROVIDER_INVALID_REQUEST"),
        () -> answersMayExist(502, "SUBMISSION_UNCLASSIFIED"),
        () -> responder.set(ex -> send(ex, 500, "{\"detail\":\"boom\"}")))) {
      submissions.set(0);
      scenario.run();

      try {
        submit();
      } catch (RuntimeException expected) {
        // classification is the subject; the throw is not
      }

      // Whatever the client concludes, it asks the provider exactly once. A retry here is how one
      // logical request becomes two provider executions.
      assertThat(submissions.get()).isEqualTo(1);
    }
  }
}
