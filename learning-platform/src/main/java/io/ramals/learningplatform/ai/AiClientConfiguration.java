package io.ramals.learningplatform.ai;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the AI call path.
 *
 * <p>The default is <em>unavailable</em>. With no base URL configured, the platform gets a port that
 * refuses immediately, and tutoring is simply absent — which is the same state as the AI plane being
 * down, and therefore the same code path that M1-T08's acceptance criterion exercises.
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

  @Bean
  public TutorPort tutorPort(
      @Value("${ramals.ai.base-url:}") String baseUrl, AiCallGuard guard) {

    if (baseUrl == null || baseUrl.isBlank()) {
      LOGGER.atInfo()
          .addKeyValue("operation", "ai.client.configure")
          .addKeyValue("aiConfigured", false)
          .log("no AI base URL configured; tutoring is unavailable and learning is unaffected");
      return new UnconfiguredTutorPort();
    }

    LOGGER.atInfo()
        .addKeyValue("operation", "ai.client.configure")
        .addKeyValue("aiConfigured", true)
        .log("AI tutoring client configured");

    // Doc 01 INTERACTIVE_AI budgets, applied at the transport. Without a read timeout a stalled AI
    // plane holds a request thread indefinitely, which is the failure the bulkhead bounds but should
    // not have to.
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(RamalsAiTutorClient.CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(RamalsAiTutorClient.READ_TIMEOUT);

    RestClient restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build();

    return new RamalsAiTutorClient(restClient, guard);
  }

  @Bean
  public AdaptationPort adaptationPort(
      @Value("${ramals.ai.base-url:}") String baseUrl, AiCallGuard guard) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return (request, deadlineMillis) -> {
        throw new AiUnavailableException("AI_NOT_CONFIGURED", "Adaptation is not enabled in this environment.");
      };
    }

    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(RamalsAiTutorClient.CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(RamalsAiTutorClient.READ_TIMEOUT);
    RestClient restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build();
    return new RamalsAiAdaptationClient(restClient, guard);
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
          "Tutoring is not enabled in this environment.");
    }
  }
}
