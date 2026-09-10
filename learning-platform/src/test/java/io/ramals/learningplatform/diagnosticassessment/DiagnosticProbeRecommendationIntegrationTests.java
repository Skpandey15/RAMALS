package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ramals.learningplatform.ai.AiCallGuard;
import io.ramals.learningplatform.ai.DelegatedAiContextMinter;
import io.ramals.learningplatform.ai.DiagnosticProbePort;
import io.ramals.learningplatform.ai.DomainContextAssembler;
import io.ramals.learningplatform.ai.RamalsAiDiagnosticProbeClient;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import io.ramals.learningplatform.assessment.DiagnosticReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-032 step 3, requirement 17: the full seam, end to end, without a live model.
 *
 * <p>Real {@link RamalsAiDiagnosticProbeClient} -> a local HTTP server standing in for the
 * Python/FastAPI diagnostic-probe reasoner -> a canned {@code AiProposalEnvelope} on the wire ->
 * real {@link DiagnosticProbeRecommendationOrchestrator} -> real {@link
 * DiagnosticProbeProposalService} + {@link DiagnosticProbeProposalGate} (PR #266, unchanged) -> a
 * recorded decision. Proves the request the client sends is well-formed and correctly targeted, and
 * that a Python-shaped 200 response flows through the deterministic gate to an audited verdict.
 */
class DiagnosticProbeRecommendationIntegrationTests {

  private static final String WORKLOAD_TOKEN = "test-workload-token";
  private static final UUID LEARNER = UUID.fromString("01900000-0000-7000-8000-0000000000c1");
  private static final UUID MC = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-0000000000b1");
  private static final UUID EV_1 = UUID.fromString("01900000-0000-7000-8000-0000000000e1");
  private static final String DOMAIN = "KAFKA";
  private static final String INTERACTION = "int-1";
  private static final String PATH = "/internal/v1/diagnostic-probe/propose";

  private record Captured(String method, String path, String authorization, String body) {}

  private final List<Captured> received = new CopyOnWriteArrayList<>();

  private final DiagnosticProbeTargetPort targetPort =
      new DiagnosticProbeTargetPort() {
        @Override
        public Optional<ResolvedMisconception> findMisconception(UUID id) {
          return MC.equals(id)
              ? Optional.of(new ResolvedMisconception(id, true, OBJECTIVE, null))
              : Optional.empty();
        }

        @Override
        public Optional<DiagnosticProbeProposal.TargetNode.Kind> findDiagnosticNodeKind(UUID id) {
          return Optional.empty();
        }
      };

  private static final class RecordingDecisionPort implements DiagnosticProbeProposalDecisionPort {
    final List<DiagnosticProbeProposalDecision> appended = new ArrayList<>();

    @Override
    public void append(DiagnosticProbeProposalDecision decision) {
      appended.add(decision);
    }

    @Override
    public Optional<RecordedProbeDecision> findByProposalId(String proposalId) {
      return appended.stream()
          .filter(d -> d.proposalId().equals(proposalId))
          .findFirst()
          .map(
              d ->
                  new RecordedProbeDecision(
                      d.proposalId(), d.interactionId(), d.accepted(),
                      d.reasonCodes(), d.parserReasonCode(), d.policyVersion()));
    }
  }

  private HttpServer aiPlane(String responseBody, int statusCode) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        PATH,
        exchange -> {
          capture(exchange);
          byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(statusCode, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.setExecutor(null);
    server.start();
    return server;
  }

  private void capture(HttpExchange exchange) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    exchange.getRequestBody().transferTo(body);
    received.add(
        new Captured(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            body.toString(StandardCharsets.UTF_8)));
  }

  private DiagnosticProbePort clientFor(HttpServer server) {
    org.springframework.http.client.SimpleClientHttpRequestFactory factory =
        new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2_000);
    factory.setReadTimeout(5_000);
    return new RamalsAiDiagnosticProbeClient(
        org.springframework.web.client.RestClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .requestFactory(factory)
            .build(),
        new AiCallGuard(3, Duration.ofSeconds(30), 4, Instant::now),
        () -> WORKLOAD_TOKEN);
  }

  private DiagnosticProbeRecommendationOrchestrator orchestrator(DiagnosticProbePort probePort) {
    DiagnosticProbeContextAssembler assembler = mock(DiagnosticProbeContextAssembler.class);
    when(assembler.assemble(eq(LEARNER), eq(DOMAIN), eq(INTERACTION)))
        .thenReturn(
            new DiagnosticProbeContextAssembler.Assembled(
                new DiagnosticProbeProposalContext(
                    LEARNER,
                    INTERACTION,
                    DOMAIN,
                    Set.of(MC),
                    Set.of(EV_1.toString()),
                    Set.of(),
                    Set.of("diagnostics.current-domain-report")),
                true,
                DiagnosticReport.DiagnosticDataStatus.HAS_EVIDENCE));

    DomainContextAssembler domainContextAssembler = mock(DomainContextAssembler.class);
    when(domainContextAssembler.forDomain(eq(DOMAIN)))
        .thenReturn(Optional.of(new DomainContext(DOMAIN, DomainType.TECHNOLOGY, "v1")));

    return new DiagnosticProbeRecommendationOrchestrator(
        assembler,
        domainContextAssembler,
        probePort,
        new DiagnosticProbeProposalService(
            new DiagnosticProbeProposalGate(), targetPort, decisions),
        new DelegatedAiContextMinter(Optional.empty()));
  }

  private final RecordingDecisionPort decisions = new RecordingDecisionPort();

  private static String proposalEnvelope(String proposalJson) {
    return "{\"contractVersion\":\"1.0\",\"proposalId\":\"prop-1\",\"agentType\":\"DIAGNOSTIC\","
        + "\"agentVersion\":\"DIAGNOSTIC_PROBE_AGENT_V1\",\"agentRunId\":\"run-1\","
        + "\"promptTemplateId\":\"DIAGNOSTIC_PROBE_CANDIDATE\","
        + "\"promptVersion\":\"DIAGNOSTIC_PROBE_PROMPT_V2\",\"modelRoute\":\"diagnostic-default\","
        + "\"trustLevel\":\"NON_AUTHORITATIVE\",\"proposal\":"
        + proposalJson
        + "}";
  }

  private static String validProposalJson() {
    return "{\"contractVersion\":\"1.0\",\"proposalType\":\"DIAGNOSTIC_PROBE_CANDIDATE\","
        + "\"proposalId\":\"prop-1\",\"requestId\":\"req-1\",\"agentRunId\":\"run-1\","
        + "\"interactionId\":\""
        + INTERACTION
        + "\",\"domain\":\""
        + DOMAIN
        + "\",\"targetMisconceptionId\":\""
        + MC
        + "\",\"targetNode\":{\"kind\":\"LEARNING_OBJECTIVE\",\"id\":\""
        + OBJECTIVE
        + "\"},\"probeIntent\":\"COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE\","
        + "\"candidateProbeRef\":null,\"evidenceRefs\":[\""
        + EV_1
        + "\"],\"rationale\":\"Additional discriminating evidence would narrow the ambiguity.\"}";
  }

  @Test
  @DisplayName("17 -- Java -> Python stub -> proposal -> Java gate: a valid recommendation is "
      + "recorded ACCEPTED")
  void javaToStubToGateAcceptsAValidRecommendation() throws IOException {
    HttpServer server = aiPlane(proposalEnvelope(validProposalJson()), 200);
    try {
      DiagnosticProbeProposalService.Outcome outcome =
          orchestrator(clientFor(server))
              .recommendNextProbe(LEARNER, DOMAIN, INTERACTION, "req-1", 12_000);

      assertThat(outcome.status())
          .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ACCEPTED);
    } finally {
      server.stop(0);
    }

    assertThat(received)
        .singleElement()
        .satisfies(
            captured -> {
              assertThat(captured.method()).isEqualTo("POST");
              assertThat(captured.path()).isEqualTo(PATH);
              assertThat(captured.authorization()).isEqualTo("Bearer " + WORKLOAD_TOKEN);
              assertThat(captured.body()).contains("\"interactionId\":\"" + INTERACTION + "\"");
              assertThat(captured.body()).contains("\"domainCode\":\"" + DOMAIN + "\"");
              assertThat(captured.body()).contains("\"learnerRef\":\"" + LEARNER + "\"");
            });
    assertThat(decisions.appended).singleElement().satisfies(row -> {
      assertThat(row.accepted()).isTrue();
      assertThat(row.interactionId()).isEqualTo(INTERACTION);
      assertThat(row.policyVersion()).isEqualTo("DIAGNOSTIC_PROBE_PROPOSAL_GATE_V1");
    });
  }

  @Test
  @DisplayName("Java -> stub -> gate: a 200 whose proposal violates the Java contract -> MALFORMED, "
      + "one audit row")
  void javaToStubToGateRecordsAMalformedReturnedProposal() throws IOException {
    String missingRationale = validProposalJson().replace(
        ",\"rationale\":\"Additional discriminating evidence would narrow the ambiguity.\"", "");
    HttpServer server = aiPlane(proposalEnvelope(missingRationale), 200);
    try {
      DiagnosticProbeProposalService.Outcome outcome =
          orchestrator(clientFor(server))
              .recommendNextProbe(LEARNER, DOMAIN, INTERACTION, "req-1", 12_000);

      assertThat(outcome.status())
          .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.MALFORMED);
    } finally {
      server.stop(0);
    }

    assertThat(decisions.appended).singleElement()
        .satisfies(row -> assertThat(row.accepted()).isFalse());
  }

  @Test
  @DisplayName("Java -> stub -> gate: the Python plane answering 422 (unprocessable) -> ABSENT, "
      + "nothing written")
  void javaToStubToGateResolvesAValidationFailureToAbsent() throws IOException {
    HttpServer server =
        aiPlane(
            "{\"type\":\"about:blank\",\"title\":\"AI request failed\",\"status\":422,"
                + "\"code\":\"UNPROCESSABLE_PROPOSAL\",\"detail\":\"no valid proposal\"}",
            422);
    try {
      DiagnosticProbeProposalService.Outcome outcome =
          orchestrator(clientFor(server))
              .recommendNextProbe(LEARNER, DOMAIN, INTERACTION, "req-1", 12_000);

      assertThat(outcome.status())
          .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    } finally {
      server.stop(0);
    }

    assertThat(decisions.appended).isEmpty();
  }
}
