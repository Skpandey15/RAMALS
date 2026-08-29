package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * What the Contract B switches do, and — more importantly — what they do not.
 *
 * <p>Three switches, all off by default, and the property that matters most is that the off state is
 * an <em>absence</em> rather than a refusal. The commissioning service, its controller and the purge
 * worker are all conditional beans: with the flags off they do not exist, so there is no code path
 * to reason about and nothing to probe. A runtime {@code if} would be a thing someone could later
 * get wrong; a missing bean is not.
 *
 * <p>These are unit tests over the wiring contract rather than a Spring context, deliberately.
 * Booting a context to assert that a bean is absent proves the context is configured the way the
 * test configured it; asserting the condition on the class proves the shipped default.
 */
class ContractBActivationSurfaceTests {

  // ================================================================================================
  // Disabled by default — and disabled means absent
  // ================================================================================================

  @Test
  @DisplayName("all three Contract B switches are off in the shipped defaults")
  void everySwitchIsOffByDefault() {
    ContractBProperties defaults = new ContractBProperties();

    // The route spends money, the worker calls a paid provider, and the purge deletes. None of the
    // three is a safe thing to acquire by upgrading.
    assertThat(defaults.isEnabled()).as("the commissioning route").isFalse();
    assertThat(defaults.getReconciliation().isEnabled()).as("the worker").isFalse();
    assertThat(defaults.getPurge().isEnabled()).as("the retention sweep").isFalse();
  }

  @Test
  @DisplayName("with Contract B disabled the commissioning beans do not exist at all")
  void commissioningIsAbsentWhenDisabled() {
    for (Class<?> bean : List.of(
        ContractBCommissioningService.class, ContractBAdminController.class)) {
      ConditionalOnProperty condition = bean.getAnnotation(ConditionalOnProperty.class);

      assertThat(condition).as("%s must be conditional, not runtime-guarded", bean.getSimpleName())
          .isNotNull();
      assertThat(condition.prefix()).isEqualTo("ramals.contract-b");
      assertThat(condition.name()).containsExactly("enabled");
      assertThat(condition.havingValue()).isEqualTo("true");
      // No matchIfMissing: an absent property must leave the bean absent, so a deployment that has
      // never heard of Contract B does not acquire it by upgrading.
      assertThat(condition.matchIfMissing()).isFalse();
    }
  }

  @Test
  @DisplayName("the purge scheduler is separately flagged, and off")
  void thePurgeSchedulerIsSeparatelyFlagged() {
    ConditionalOnProperty condition =
        ContractBPurgeWorker.class.getAnnotation(ConditionalOnProperty.class);

    // Separate from reconciliation on purpose: one drives executions forward, the other deletes
    // result material. An operator enabling recovery must not silently acquire a scheduled delete.
    assertThat(condition).isNotNull();
    assertThat(condition.name()).containsExactly("purge.enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
    assertThat(new ContractBProperties().getPurge().isEnabled()).isFalse();
  }

  @Test
  @DisplayName("the commissioning route is admin-gated and is not a public endpoint")
  void theRouteIsAdminGated() {
    org.springframework.security.access.prepost.PreAuthorize authorization =
        ContractBAdminController.class.getAnnotation(
            org.springframework.security.access.prepost.PreAuthorize.class);
    org.springframework.web.bind.annotation.RequestMapping mapping =
        ContractBAdminController.class.getAnnotation(
            org.springframework.web.bind.annotation.RequestMapping.class);

    // Commissioning starts paid work that outlives the request. Nobody should reach it by accident,
    // and no unauthenticated caller should reach it at all.
    assertThat(authorization).isNotNull();
    assertThat(authorization.value()).contains("ADMIN");
    assertThat(mapping.value()[0]).startsWith("/api/v1/admin/");
  }

  // ================================================================================================
  // Enabled — exactly one execution, and the lifecycle's semantics unchanged
  // ================================================================================================

  /** Records what the lifecycle was asked to do, without doing any of it. */
  private static class RecordingLifecycle extends ContractBExecutionService {

    private final List<String> admitted = new ArrayList<>();
    private final List<DurableSubmissionCommand> submitted = new ArrayList<>();
    private DurableExecutionState outcome = DurableExecutionState.SUBMITTED;

    RecordingLifecycle() {
      super(null, null, null, null, null, new ContractBProperties());
    }

    @Override
    public boolean admit(String requestId, String idempotencyKey, String provider, String model,
        String modelRoute) {
      admitted.add(requestId);
      return true;
    }

    @Override
    public DurableExecutionState submit(String requestId, DurableSubmissionCommand command) {
      submitted.add(command);
      return outcome;
    }
  }

  @Test
  @DisplayName("commissioning admits exactly one execution and submits it exactly once")
  void commissioningAdmitsAndSubmitsOnce() {
    RecordingLifecycle lifecycle = new RecordingLifecycle();

    ContractBCommissioningService.Commissioned commissioned =
        new ContractBCommissioningService(lifecycle)
            .commission("claude-haiku-4-5-20251001", "diagnostic", "hello", 64);

    assertThat(lifecycle.admitted).hasSize(1);
    assertThat(lifecycle.submitted).hasSize(1);
    assertThat(commissioned.requestId()).isEqualTo(lifecycle.admitted.get(0));
  }

  @Test
  @DisplayName("admission happens before submission — the write-ahead order")
  void admissionPrecedesSubmission() {
    List<String> order = new ArrayList<>();
    ContractBExecutionService lifecycle = new ContractBExecutionService(
        null, null, null, null, null, new ContractBProperties()) {
      @Override
      public boolean admit(String requestId, String idempotencyKey, String provider, String model,
          String modelRoute) {
        order.add("admit");
        return true;
      }

      @Override
      public DurableExecutionState submit(String requestId, DurableSubmissionCommand command) {
        order.add("submit");
        return DurableExecutionState.SUBMITTED;
      }
    };

    new ContractBCommissioningService(lifecycle).commission("m", "diagnostic", "p", 64);

    // The durable row must exist before the provider is called. Reversed, a process that died
    // mid-call would leave a provider execution nothing knows about -- unrecoverable rather than
    // merely unacknowledged.
    assertThat(order).containsExactly("admit", "submit");
  }

  @Test
  @DisplayName("identifiers are server-derived and never taken from the caller")
  void identifiersAreServerDerived() {
    RecordingLifecycle lifecycle = new RecordingLifecycle();
    ContractBCommissioningService commissioning = new ContractBCommissioningService(lifecycle);

    commissioning.commission("m", "diagnostic", "p", 64);
    commissioning.commission("m", "diagnostic", "p", 64);

    // The Definition of Done requires the custom_id the provider sees to be server-derived.
    // Correlation a caller could choose is correlation that can be aimed at another execution --
    // and two commissionings of an identical request must still be two distinct executions.
    DurableSubmissionCommand first = lifecycle.submitted.get(0);
    DurableSubmissionCommand second = lifecycle.submitted.get(1);
    assertThat(first.idempotencyKey()).isNotEqualTo(second.idempotencyKey());
    assertThat(first.idempotencyKey()).contains(first.requestId());
    assertThat(first.requestDigest()).hasSize(64).isEqualTo(second.requestDigest());
  }

  @Test
  @DisplayName("an ambiguous submission is reported as UNKNOWN_TERMINAL, not as an error")
  void ambiguityIsReportedNotThrown() {
    RecordingLifecycle lifecycle = new RecordingLifecycle();
    lifecycle.outcome = DurableExecutionState.UNKNOWN_TERMINAL;

    ContractBCommissioningService.Commissioned commissioned =
        new ContractBCommissioningService(lifecycle).commission("m", "diagnostic", "p", 64);

    // An ambiguous submission is an expected outcome of a provider with no replay-safe admission.
    // Raising here would invite the caller to retry, which is the one thing that must never follow.
    assertThat(commissioned.state()).isEqualTo(DurableExecutionState.UNKNOWN_TERMINAL);
    assertThat(lifecycle.submitted).hasSize(1);
  }

  @Test
  @DisplayName("a refused admission never proceeds to submit")
  void arefusedAdmissionDoesNotSubmit() {
    RecordingLifecycle lifecycle = new RecordingLifecycle() {
      @Override
      public boolean admit(String requestId, String idempotencyKey, String provider, String model,
          String modelRoute) {
        return false;
      }
    };

    // Submitting against a row this call does not own would be submitting on someone else's behalf.
    assertThatThrownBy(() -> new ContractBCommissioningService(lifecycle)
        .commission("m", "diagnostic", "p", 64))
        .isInstanceOf(IllegalStateException.class);
    assertThat(lifecycle.submitted).isEmpty();
  }

  @Test
  @DisplayName("the prompt is digested, never carried in the digest field")
  void thePromptIsHashedNotCopied() {
    RecordingLifecycle lifecycle = new RecordingLifecycle();

    new ContractBCommissioningService(lifecycle)
        .commission("m", "diagnostic", "CANARY-LEARNER-TEXT", 64);

    // The digest travels to the AI plane. A plain copy would put learner-derived text somewhere it
    // has no reason to be.
    assertThat(lifecycle.submitted.get(0).requestDigest())
        .doesNotContain("CANARY-LEARNER-TEXT")
        .matches("[0-9a-f]{64}");
  }
}
