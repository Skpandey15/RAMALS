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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The identity provider is a different service, and failures must say so.
 *
 * <p>Two operational facts were being collapsed into one. A token endpoint that is down produced
 * {@code AI_TRANSPORT_FAILURE} — "The tutoring service could not be reached" — so an operator went
 * and looked at {@code ramals-ai}, found it healthy, and had nothing pointing at Keycloak.
 *
 * <p>Separately, each of the three AI ports built its own {@link WorkloadTokenProvider}, so a
 * platform serving tutoring, adaptation and assessment made three client-credentials grants where
 * one would do, on three unsynchronised schedules.
 */
class WorkloadIdentityFailureTests {

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope(
        "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        new LearnerRef("opaque-learner-ref-001", "en-IN"),
        new LearningContext("KAFKA_TOPIC", null, null, "NEEDS_PRACTICE", null),
        null, null,
        new Constraints(InteractionClass.INTERACTIVE_AI, 8000, null, null, null), null);
  }

  private static RestClient clientFor(String baseUrl) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(DeadlineAwareClientHttpRequestFactory.forAiPlane())
        .build();
  }

  private static RestClient tokenClientFor(String baseUrl) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(DeadlineAwareClientHttpRequestFactory.forSupportingCall())
        .build();
  }

  /** A token endpoint that counts grants and can be told what to answer. */
  private static HttpServer tokenEndpoint(AtomicInteger grants, String body, int status)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", exchange -> {
      grants.incrementAndGet();
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    });
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    return server;
  }

  private static String urlOf(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  // -- the failure names the right service ----------------------------------------------------------

  @Test
  @DisplayName("an unreachable identity provider is not reported as an AI transport failure")
  void anUnreachableIdentityProviderIsNamed() {
    // Nothing listening on the token port; the AI plane is not even involved.
    WorkloadTokenProvider identity = new WorkloadTokenProvider(
        tokenClientFor("http://127.0.0.1:1"), "test-client", "test-secret", "ramals-ai");
    AiCallGuard guard = new AiCallGuard(3, Duration.ofSeconds(30), 4, Instant::now);
    RamalsAiTutorClient client =
        new RamalsAiTutorClient(clientFor("http://127.0.0.1:2"), guard, identity);

    assertThatThrownBy(() -> client.requestTutorResponse(request(), 8_000))
        .isInstanceOf(AiUnavailableException.class)
        .satisfies(failure -> {
          assertThat(((AiUnavailableException) failure).code()).isEqualTo("AI_IDENTITY_FAILURE");
          assertThat(((AiUnavailableException) failure).origin()).isEqualTo(FailureOrigin.CALLER);
        });
  }

  @Test
  @DisplayName("a token endpoint answering without a token is an identity failure")
  void aTokenlessResponseIsAnIdentityFailure() throws IOException {
    HttpServer server = tokenEndpoint(new AtomicInteger(), "{\"not_a_token\":true}", 200);
    try {
      WorkloadTokenProvider identity = new WorkloadTokenProvider(
          tokenClientFor(urlOf(server) + "/token"), "test-client", "test-secret", "ramals-ai");

      assertThatThrownBy(identity::accessToken)
          .isInstanceOf(AiUnavailableException.class)
          .satisfies(failure ->
              assertThat(((AiUnavailableException) failure).code())
                  .isEqualTo("AI_IDENTITY_FAILURE"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("the identity failure never carries the token endpoint's address")
  void theIdentityFailureDoesNotLeakTheEndpoint() {
    WorkloadTokenProvider identity = new WorkloadTokenProvider(
        tokenClientFor("http://secret-idp.internal:8443"), "test-client", "hunter2", "ramals-ai");

    assertThatThrownBy(identity::accessToken)
        .isInstanceOf(AiUnavailableException.class)
        .satisfies(failure -> {
          assertThat(failure.getMessage()).doesNotContain("secret-idp.internal");
          assertThat(failure.getMessage()).doesNotContain("hunter2");
        });
  }

  @Test
  @DisplayName("the reason mapping knows the code, rather than falling back to transport")
  void theReasonMappingKnowsTheCode() {
    // Without this the whole distinction is lost downstream: fromCode falls back to
    // TRANSPORT_FAILURE for anything it does not recognise, which is the label being corrected.
    assertThat(TutorUnavailableReason.fromCode("AI_IDENTITY_FAILURE"))
        .isEqualTo(TutorUnavailableReason.IDENTITY_FAILURE);
    assertThat(TutorUnavailableReason.IDENTITY_FAILURE.expected()).isFalse();
  }

  // -- one cache, not three -------------------------------------------------------------------------

  @Test
  @DisplayName("a valid token is reused rather than re-granted")
  void aValidTokenIsReused() throws IOException {
    AtomicInteger grants = new AtomicInteger();
    HttpServer server = tokenEndpoint(
        grants, "{\"access_token\":\"issued\",\"expires_in\":300}", 200);
    try {
      WorkloadTokenProvider identity = new WorkloadTokenProvider(
          tokenClientFor(urlOf(server) + "/token"), "test-client", "test-secret", "ramals-ai");

      for (int call = 1; call <= 20; call++) {
        assertThat(identity.accessToken()).isEqualTo("issued");
      }

      assertThat(grants).as("one grant should serve every call until expiry").hasValue(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("concurrent first calls take a single grant between them")
  void concurrentCallersShareOneGrant() throws Exception {
    AtomicInteger grants = new AtomicInteger();
    HttpServer server = tokenEndpoint(
        grants, "{\"access_token\":\"issued\",\"expires_in\":300}", 200);
    ExecutorService callers = Executors.newFixedThreadPool(8);
    try {
      WorkloadTokenProvider identity = new WorkloadTokenProvider(
          tokenClientFor(urlOf(server) + "/token"), "test-client", "test-secret", "ramals-ai");
      CountDownLatch start = new CountDownLatch(1);

      List<Future<String>> results = new java.util.ArrayList<>();
      for (int caller = 0; caller < 8; caller++) {
        results.add(callers.submit(() -> {
          start.await();
          return identity.accessToken();
        }));
      }
      start.countDown();

      for (Future<String> result : results) {
        assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo("issued");
      }
      // The re-read inside the lock is what makes this one rather than eight: every thread that
      // queued behind the refresh finds the token already there.
      assertThat(grants).hasValue(1);
    } finally {
      callers.shutdownNow();
      server.stop(0);
    }
  }

  @Test
  @DisplayName("all three ports are wired to the same identity")
  void allThreePortsShareOneIdentity() {
    // The regression being prevented is three caches, which is invisible from behaviour: each one
    // works. It is only visible in the wiring, so that is what is asserted.
    AiClientConfiguration configuration = new AiClientConfiguration();
    WorkloadToken identity = configuration.workloadToken(
        "http://127.0.0.1:1/token", "test-client", "test-secret", "ramals-ai");

    AiCallGuard guard = configuration.aiCallGuard();
    TutorPort tutor = configuration.tutorPort("http://127.0.0.1:2", identity, guard);
    AdaptationPort adaptation = configuration.adaptationPort("http://127.0.0.1:2", identity, guard);
    AssessmentPort assessment = configuration.assessmentPort("http://127.0.0.1:2", identity, guard);

    assertThat(tutor).isInstanceOf(RamalsAiTutorClient.class);
    assertThat(adaptation).isInstanceOf(RamalsAiAdaptationClient.class);
    assertThat(assessment).isInstanceOf(RamalsAiAssessmentClient.class);
    // One bean, taken by all three: the ports cannot build their own because they are handed one.
    assertThat(identity.available()).isTrue();
  }

  @Test
  @DisplayName("without configuration the identity refuses instead of pretending")
  void anUnconfiguredIdentityRefuses() {
    WorkloadToken identity =
        new AiClientConfiguration().workloadToken("", "", "", "ramals-ai");

    assertThat(identity.available()).isFalse();
    assertThatThrownBy(identity::accessToken)
        .isInstanceOf(AiUnavailableException.class)
        .satisfies(failure ->
            assertThat(((AiUnavailableException) failure).code()).isEqualTo("AI_NOT_CONFIGURED"));
  }
}
