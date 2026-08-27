package io.ramals.learningplatform.ai;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the AI call path.
 *
 * <p>The default is <em>unavailable</em>. Without both a base URL and a workload identity, the
 * platform gets a port that refuses immediately, and tutoring is simply absent — which is the same
 * state as the AI plane being down, and therefore the same code path that M1-T08's acceptance
 * criterion exercises.
 *
 * <p>Both halves are required because the AI plane authenticates every agent endpoint. A base URL
 * without an identity produces a client that reaches the service and is rejected by it, which is
 * indistinguishable at the call site from the service being unreachable — a configuration mistake
 * wearing the costume of a healthy degradation.
 *
 * <p>That is deliberate. The alternative — failing to start without an AI base URL — would couple
 * deterministic availability to AI configuration, which is precisely what this task exists to
 * prevent. A developer with no AI plane running should get a working platform and no tutor, not a
 * stack trace.
 */
@Configuration
public class AiClientConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(AiClientConfiguration.class);

  /** Consecutive transport failures before the breaker opens. */
  private static final int FAILURE_THRESHOLD = 3;

  /** How long the breaker stays open before allowing one probe. */
  private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

  /**
   * Concurrent AI calls allowed.
   *
   * <p>Small on purpose. The bulkhead's job is to bound how much of the request pool a slow AI plane
   * can consume, and a limit close to the pool size would not bound anything.
   */
  private static final int MAX_CONCURRENT_CALLS = 8;

  @Bean
  public AiCallGuard aiCallGuard() {
    return new AiCallGuard(FAILURE_THRESHOLD, OPEN_DURATION, MAX_CONCURRENT_CALLS, Instant::now);
  }

  /**
   * The workload identity every AI client needs, or empty when this environment has none.
   *
   * <p>One factory for all three ports, deliberately. Two of them previously built a client without
   * one and every call they made was rejected with 401 — which the clients turned into "the AI plane
   * could not be reached", so the platform reported a healthy degradation while the feature was
   * entirely off. Sharing the check means "configured for AI" cannot mean different things to
   * different ports.
   */
  @Bean
  public WorkloadToken workloadToken(
      @Value("${ramals.ai.workload-token-url:}") String tokenUrl,
      @Value("${ramals.ai.workload-client-id:}") String clientId,
      @Value("${ramals.ai.workload-client-secret:}") String clientSecret,
      @Value("${ramals.ai.workload-audience:ramals-ai}") String audience) {
    if (tokenUrl == null || tokenUrl.isBlank()
        || clientId == null || clientId.isBlank()
        || clientSecret == null || clientSecret.isBlank()) {
      return WorkloadToken.unavailable("No AI workload identity is configured in this environment.");
    }
    RestClient tokenClient =
        RestClient.builder().baseUrl(tokenUrl).requestFactory(tokenRequestFactory()).build();
    return new WorkloadTokenProvider(tokenClient, clientId, clientSecret, audience);
  }

  private static boolean configured(String baseUrl, WorkloadToken identity) {
    return baseUrl != null && !baseUrl.isBlank() && identity.available();
  }

  @Bean
  public TutorPort tutorPort(
      @Value("${ramals.ai.base-url:}") String baseUrl,
      WorkloadToken identity, AiCallGuard guard) {
    if (!configured(baseUrl, identity)) {
      LOGGER.atInfo()
          .addKeyValue("operation", "ai.client.configure")
          .addKeyValue("aiConfigured", false)
          .addKeyValue("baseUrlConfigured", baseUrl != null && !baseUrl.isBlank())
          .addKeyValue("workloadIdentityConfigured", identity.available())
          .log("AI tutoring is not fully configured; tutoring is unavailable and learning is "
              + "unaffected");
      return new UnconfiguredTutorPort();
    }

    LOGGER.atInfo()
        .addKeyValue("operation", "ai.client.configure")
        .addKeyValue("aiConfigured", true)
        .log("AI tutoring client configured");

    // Doc 01 INTERACTIVE_AI budgets, applied at the transport. Without a read timeout a stalled AI
    // plane holds a request thread indefinitely, which is the failure the bulkhead bounds but should
    // not have to.
    DeadlineAwareClientHttpRequestFactory requestFactory = configuredRequestFactory();

    RestClient restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build();

    return new RamalsAiTutorClient(restClient, guard, identity);
  }

  @Bean
  public AdaptationPort adaptationPort(
      @Value("${ramals.ai.base-url:}") String baseUrl,
      WorkloadToken identity, AiCallGuard guard) {
    if (!configured(baseUrl, identity)) {
      return (request, deadlineMillis) -> {
        throw new AiUnavailableException("AI_NOT_CONFIGURED",
            "Adaptation is not enabled in this environment.", FailureOrigin.CALLER);
      };
    }

    DeadlineAwareClientHttpRequestFactory requestFactory = configuredRequestFactory();
    RestClient restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build();
    return new RamalsAiAdaptationClient(restClient, guard, identity);
  }

  @Bean
  public DiagnosticAssessmentPort diagnosticAssessmentPort(
      @Value("${ramals.ai.base-url:}") String baseUrl,
      WorkloadToken identity, AiCallGuard guard) {
    if (!configured(baseUrl, identity)) {
      // The same unconfigured behaviour every other agent gets: a deployment without an AI plane
      // must start and serve, and refuse the call it cannot make rather than fail at wiring time.
      return (request, authorization, deadlineMillis) -> {
        throw new AiUnavailableException("AI_NOT_CONFIGURED",
            "Diagnostic assessment is not enabled in this environment.", FailureOrigin.CALLER);
      };
    }

    DeadlineAwareClientHttpRequestFactory requestFactory = configuredRequestFactory();
    RestClient restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build();
    return new RamalsAiDiagnosticAssessmentClient(restClient, guard, identity);
  }

  @Bean
  public AssessmentPort assessmentPort(
      @Value("${ramals.ai.base-url:}") String baseUrl,
      WorkloadToken identity, AiCallGuard guard) {
    if (!configured(baseUrl, identity)) {
      return (request, deadlineMillis, requestedDifficulty) -> {
        throw new AiUnavailableException("AI_NOT_CONFIGURED",
            "Assessment commissioning is not enabled in this environment.", FailureOrigin.CALLER);
      };
    }
    DeadlineAwareClientHttpRequestFactory requestFactory = configuredRequestFactory();
    RestClient aiClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    return new RamalsAiAssessmentClient(aiClient, guard, identity);
  }

  private static DeadlineAwareClientHttpRequestFactory configuredRequestFactory() {
    return configured(DeadlineAwareClientHttpRequestFactory.forAiPlane());
  }

  /**
   * The transport for the workload-token call.
   *
   * <p>Same deadline discipline, different attribution. Reaching the identity provider is not
   * evidence about the AI plane, so a budget spent entirely on a slow token endpoint must not open
   * the AI plane's circuit.
   */
  private static DeadlineAwareClientHttpRequestFactory tokenRequestFactory() {
    return configured(DeadlineAwareClientHttpRequestFactory.forSupportingCall());
  }

  private static DeadlineAwareClientHttpRequestFactory configured(
      DeadlineAwareClientHttpRequestFactory requestFactory) {
    requestFactory.setConnectTimeout(RamalsAiTutorClient.CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(RamalsAiTutorClient.READ_TIMEOUT);
    return requestFactory;
  }

  /**
   * The port used when no AI plane is configured.
   *
   * <p>Refuses without attempting anything, exactly as an open circuit would. Having a real bean
   * rather than a null means every caller takes the same degradation path whether the AI plane is
   * absent, unreachable or overloaded — one behaviour to reason about instead of three.
   */
  static final class UnconfiguredTutorPort implements TutorPort {

    @Override
    public io.ramals.learningplatform.ai.contract.AiProposalEnvelope requestTutorResponse(
        io.ramals.learningplatform.ai.contract.AiRequestEnvelope request, long deadlineMillis) {
      throw new AiUnavailableException("AI_NOT_CONFIGURED",
          "Tutoring is not enabled in this environment.", FailureOrigin.CALLER);
    }
  }
}
