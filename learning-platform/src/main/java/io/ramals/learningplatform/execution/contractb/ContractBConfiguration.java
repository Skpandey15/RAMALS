package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.ai.WorkloadToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        RestClient.builder().baseUrl(baseUrl).build(), identity);
  }

  /** Present so the platform starts without an AI plane, and refuses every Contract B call. */
  static final class UnconfiguredDurableExecutionPort implements DurableExecutionPort {

    @Override
    public DurableSubmissionAck submit(DurableSubmissionCommand command) {
      throw new DurableSubmissionAmbiguousException(
          command.requestId(), "no durable execution plane is configured");
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
