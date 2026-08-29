package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.ai.WorkloadToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the Contract B execution path.
 *
 * <p>Registered unconditionally and inert without configuration, matching
 * {@code ResultEncryptionConfiguration}. With no AI base URL the port refuses every call rather than
 * failing at wiring time — a deployment without an AI plane must still start and serve, which is the
 * behaviour every other agent client in this codebase already has.
 *
 * <p>Nothing calls the lifecycle service yet. The public Contract B route does not exist, and
 * {@link ContractBProperties#isEnabled()} is false; the beans are here so the path can be exercised
 * by tests and by crash/recovery qualification, which is what must happen before a route is opened.
 */
@Configuration
@EnableConfigurationProperties(ContractBProperties.class)
public class ContractBConfiguration {

  /**
   * The AI plane's durable surface, or a refusal.
   *
   * <p>The unconfigured implementation refuses rather than returning null, and it refuses with
   * {@link DurableSubmissionAmbiguousException} for submissions specifically. That looks
   * counter-intuitive — nothing was sent, so surely it is a definite failure — and it is the safe
   * direction: a caller that treats "not configured" as a definite failure could conclude the
   * provider is idle, and the one action that follows from that conclusion is a resubmission. There
   * is no configuration in which refusing to submit and refusing to guess are both wrong.
   */
  @Bean
  public DurableExecutionPort durableExecutionPort(
      @Value("${ramals.ai.base-url:}") String baseUrl, WorkloadToken identity) {
    if (baseUrl == null || baseUrl.isBlank() || identity == null) {
      return new UnconfiguredDurableExecutionPort();
    }
    return new RamalsAiDurableExecutionClient(
        RestClient.builder().baseUrl(baseUrl).requestFactory(durableRequestFactory()).build(),
        identity);
  }

  /**
   * The transport for Contract B calls: HTTP/1.1, buffered, with explicit timeouts.
   *
   * <p><strong>Naming a factory at all is the fix.</strong> Built without one, {@code RestClient}
   * uses Spring's default JDK {@code HttpClient} transport, which offers an HTTP/2 cleartext
   * upgrade. The AI plane is served by uvicorn, which speaks HTTP/1.1 only: it logs
   * {@code Unsupported upgrade request} / {@code Invalid HTTP request received}, and the request
   * reaches the route with no body. Every Contract B submission was therefore rejected as malformed
   * while the payload the client built was perfectly correct — which is why this survived a suite
   * that asserts hard on that payload. {@code SimpleClientHttpRequestFactory} is
   * {@code HttpURLConnection}-based, attempts no upgrade, and the body arrives.
   *
   * <p>Reproduced against the real ASGI stack before this changed: 422 with the upgrade warning,
   * then 201 without it. Every other AI client in this codebase already passes a factory of this
   * kind. Contract B was the one that did not, and it was the one that could not submit.
   *
   * <p>Not shared with {@code DeadlineAwareClientHttpRequestFactory}, deliberately. That transport
   * carries Contract A's interactive deadline: a per-request budget a caller consumes and that
   * expires. Contract B commissions durable work whose whole premise is that it outlives the call
   * that started it, so inheriting an interactive budget would impose the wrong failure mode on the
   * wrong contract.
   *
   * <p>The timeouts bound the call, never the work. Thirty seconds is far longer than acknowledging
   * a batch submission takes and far shorter than a batch runs, which is the gap this has to sit in:
   * a submission unacknowledged after thirty seconds is ambiguous, and M2-ADR-016 already says what
   * to do about that. Before this, Contract B had no client timeout at all, so an unresponsive AI
   * plane held a reconciliation thread indefinitely.
   */
  private static ClientHttpRequestFactory durableRequestFactory() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(30));
    return factory;
  }

  /** Present so the platform starts without an AI plane, and refuses every Contract B call. */
  static final class UnconfiguredDurableExecutionPort implements DurableExecutionPort {

    @Override
    public DurableSubmissionAck submit(DurableSubmissionCommand command) {
      throw new DurableSubmissionAmbiguousException(
          command.requestId(), "no durable execution plane is configured");
    }

    @Override
    public DurableExecutionSearch search(String customId, String from, String to,
        int maxInspections, java.util.Collection<String> excludeIds) {
      throw new IllegalStateException("no durable execution plane is configured");
    }

    @Override
    public DurableStatusSnapshot status(String providerExecutionId) {
      throw new IllegalStateException("no durable execution plane is configured");
    }

    @Override
    public DurableResultRecord result(String providerExecutionId, String customId) {
      throw new IllegalStateException("no durable execution plane is configured");
    }
  }
}
