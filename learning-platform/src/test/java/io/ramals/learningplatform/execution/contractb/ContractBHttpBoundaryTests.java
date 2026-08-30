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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the Contract B client actually puts on the wire, observed at a real HTTP boundary.
 *
 * <p>Every other Contract B test hands the lifecycle a fake port, so nothing in the suite had ever
 * seen a byte this client sends. The first real end-to-end run against the AI plane found two
 * defects within minutes — an empty request body and an empty correlation header — and both were
 * invisible precisely because the boundary was never crossed. These tests cross it.
 *
 * <p>The server here is a plain JDK {@link HttpServer} that records the raw request. Deliberately
 * not a mock and not MockRestServiceServer: the failure being guarded against is a body that never
 * gets serialised, and a mock that intercepts above the converters would have reported success on
 * the exact bug that reached production code.
 *
 * <p>The client under test is built by {@link ContractBConfiguration} itself rather than assembled
 * here, so the wiring being asserted is the wiring that ships.
 */
class ContractBHttpBoundaryTests {

  private HttpServer server;
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private String baseUrl;

  /** One request, as it arrived. */
  private record Recorded(String method, String path, String query, String body,
      Map<String, List<String>> headers) {

    String header(String name) {
      List<String> values = headers.get(name);
      return values == null || values.isEmpty() ? null : values.get(0);
    }
  }

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::record);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private void record(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    requests.add(new Recorded(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getPath(),
        exchange.getRequestURI().getQuery(),
        new String(body, StandardCharsets.UTF_8),
        Map.copyOf(exchange.getRequestHeaders())));

    byte[] response = """
        {"provider_execution_id":"msgbatch_boundary0001","state":"RUNNING",\
        "custom_id":"idem-req-boundary","created_at":"2026-08-29T00:00:00+00:00",\
        "expires_at":"2026-08-30T00:00:00+00:00","outcome":"ZERO","matches":[],\
        "batches_listed":0,"batches_inspected":0,"batches_uninspectable":0,"pages_fetched":1,\
        "limit_reached":null,"batches_excluded":0,"newly_excluded_ids":[]}"""
        .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private DurableExecutionPort port() {
    return new ContractBConfiguration().durableExecutionPort(baseUrl, () -> "test-workload-token");
  }

  private static DurableSubmissionCommand command() {
    return new DurableSubmissionCommand(
        "req-boundary", "idem-req-boundary",
        "0000000000000000000000000000000000000000000000000000000000000001",
        "claude-haiku-4-5-20251001", 64,
        List.of(new DurableSubmissionCommand.Turn("user", "hello")));
  }

  // ================================================================================================
  // D2: the request body reaches the wire
  // ================================================================================================

  @Test
  @DisplayName("submit sends a non-empty JSON body carrying every required field")
  void submitSendsARealBody() {
    try (CorrelationContext.Scope ignored = correlated()) {
      port().submit(command());
    }

    Recorded submit = only();
    assertThat(submit.method()).isEqualTo("POST");
    assertThat(submit.path()).isEqualTo("/internal/v1/durable/executions");

    // The defect: the body arrived empty while the header still said application/json, so the AI
    // plane rejected it as a malformed request rather than as a missing one.
    assertThat(submit.body())
        .as("the submission body must not be empty -- an empty body is the D2 defect")
        .isNotBlank();
    assertThat(submit.header("Content-type")).startsWith("application/json");

    // The transport must not offer an HTTP/2 upgrade. This is the actual D2 defect: Spring's
    // default JDK HttpClient transport does, uvicorn speaks HTTP/1.1 only and rejects it, and the
    // request then reaches the route with no body at all -- so a perfectly correct payload was
    // rejected as malformed. Asserted on the headers because that is the part a local server can
    // still witness; the body assertions above would pass either way.
    assertThat(submit.header("Upgrade"))
        .as("the durable transport must not offer an HTTP/2 upgrade -- uvicorn refuses it")
        .isNull();
    assertThat(submit.header("Http2-settings")).isNull();

    // Every field the AI plane's DurableSubmitRequest requires. Asserted by name rather than by
    // parsing, because the point is what is on the wire, not what a parser can recover from it.
    assertThat(submit.body())
        .contains("\"request_id\"").contains("req-boundary")
        .contains("\"idempotency_key\"").contains("idem-req-boundary")
        .contains("\"request_digest\"")
        .contains("\"model\"").contains("claude-haiku-4-5-20251001")
        .contains("\"max_output_tokens\"")
        .contains("\"messages\"").contains("\"role\"").contains("\"content\"");
  }

  @Test
  @DisplayName("the search sends its window, budget and exclusions as query parameters")
  void searchSendsItsParameters() {
    try (CorrelationContext.Scope ignored = correlated()) {
      port().search("idem-req-boundary", "2026-08-29T00:00:00Z", "2026-08-29T02:00:00Z",
          7, List.of("msgbatch_ruled_out_a", "msgbatch_ruled_out_b"));
    }

    Recorded search = only();
    assertThat(search.method()).isEqualTo("GET");
    assertThat(search.query())
        .contains("custom_id=idem-req-boundary")
        .contains("max_inspections=7")
        .contains("exclude_id=msgbatch_ruled_out_a")
        .contains("exclude_id=msgbatch_ruled_out_b");
  }

  // ================================================================================================
  // D1: the correlation header is valid on every call
  // ================================================================================================

  @Test
  @DisplayName("every call carries a canonical interaction id, never an empty one")
  void everyCallCarriesACanonicalInteractionId() {
    try (CorrelationContext.Scope ignored = correlated()) {
      port().submit(command());
      port().search("idem-req-boundary", "2026-08-29T00:00:00Z", "2026-08-29T02:00:00Z",
          5, List.of());
    }

    assertThat(requests).hasSize(2);
    for (Recorded request : requests) {
      String interactionId = request.header("X-interaction-id");
      // The AI plane accepts a *missing* header and generates one, but rejects an empty or
      // malformed one with 400 INVALID_INTERACTION_ID. An empty string is therefore strictly worse
      // than sending nothing, which is exactly the shape D1 took.
      assertThat(interactionId).as("%s %s must carry an interaction id",
          request.method(), request.path()).isNotNull().isNotBlank();
      assertThat(UuidV7.isCanonical(interactionId))
          .as("interaction id %s must be a canonical lowercase UUIDv7", interactionId)
          .isTrue();
    }
  }

  @Test
  @DisplayName("a call from a thread with no correlation omits the header rather than sending empty")
  void aThreadWithoutCorrelationOmitsTheHeader() {
    // No scope at all -- the reconciliation worker's situation before D1 was fixed. It sent
    // "X-Interaction-ID: " and the AI plane answered 400 INVALID_INTERACTION_ID, so every recovery
    // call failed. Omitting is the contract the AI plane defines: a missing header makes it
    // generate one, an empty header is malformed. Strictly better than sending nothing is only
    // possible by establishing real correlation, which is the worker's job, not the transport's.
    // The Gradle worker is reused across Spring contexts; make the premise explicit instead of
    // inheriting MDC left by an unrelated scheduled test callback.
    org.slf4j.MDC.clear();
    port().submit(command());

    assertThat(only().header("X-interaction-id"))
        .as("an empty correlation header is worse than none -- it is refused")
        .isNull();
  }

  @Test
  @DisplayName("a supplied correlation is used rather than replaced")
  void anEstablishedCorrelationIsPreserved() {
    String established = UuidV7.generate().toString();
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(established, "0af7651916cd43dd8448eb211c80319c")) {
      port().submit(command());
    }

    // A generated fallback must never overwrite a real one, or an execution stops being traceable
    // back to the request that created it.
    assertThat(only().header("X-interaction-id")).isEqualTo(established);
  }

  @Test
  @DisplayName("the unconfigured port still refuses a submission ambiguously")
  void theUnconfiguredPortIsUnchanged() {
    DurableExecutionPort unconfigured =
        new ContractBConfiguration().durableExecutionPort("", () -> "token");

    assertThatThrownBy(() -> unconfigured.submit(command()))
        .isInstanceOf(DurableSubmissionAmbiguousException.class);
    assertThat(requests).isEmpty();
  }

  private static CorrelationContext.Scope correlated() {
    return CorrelationContext.withCorrelation(
        UuidV7.generate().toString(), "0af7651916cd43dd8448eb211c80319c");
  }

  private Recorded only() {
    assertThat(requests).as("exactly one request was expected").hasSize(1);
    return requests.get(0);
  }

}
