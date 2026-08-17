package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import java.time.Duration;
import java.time.Instant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * The M1-T08 acceptance criteria, both of which are negative properties.
 *
 * <p>"{@code ramals-ai} down does not block deterministic learning" and "no open DB transaction
 * crosses the AI call" are the kind of requirement that is satisfied by default on the day it is
 * written and quietly broken later — by an added annotation, or by a caller that starts treating an
 * empty tutor response as an error. Both are asserted against the real service, with only the
 * transport substituted.
 */
class TutorDegradationTests {

  private static final String SKILL = "KAFKA_PARTITIONING";

  /** An assembler that answers without a database, so these tests need no PostgreSQL. */
  private static DomainContextAssembler assembler() {
    return new DomainContextAssembler(null) {
      @Override
      public Optional<DomainContext> forSkill(String skillCode) {
        return SKILL.equals(skillCode)
            ? Optional.of(new DomainContext("KAFKA", DomainType.TECHNOLOGY, "v1"))
            : Optional.empty();
      }
    };
  }

  private static SimpleMeterRegistry registry;

  private static TutorService serviceWith(TutorPort port) {
    registry = new SimpleMeterRegistry();
    return new TutorService(port, assembler(), registry);
  }

  private static double outcomeCount(String outcome, String reason) {
    return registry.counter("ramals.ai.tutor.outcome", "outcome", outcome, "reason", reason).count();
  }

  // -- ramals-ai down does not block deterministic learning ------------------------------------------

  @Test
  @DisplayName("an unreachable AI plane is reported as a named failure, not an exception")
  void anUnreachableAiPlaneDegrades() {
    TutorService service = serviceWith((request, deadline) -> {
      throw new AiUnavailableException("AI_TRANSPORT_FAILURE", "unreachable");
    });

    TutorOutcome outcome = service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");

    // Named rather than empty: a caller can tell a broken AI plane from a switched-off one, and an
    // operator can see the difference without reading logs.
    assertThat(outcome).isInstanceOf(TutorOutcome.Unavailable.class);
    assertThat(((TutorOutcome.Unavailable) outcome).reason())
        .isEqualTo(TutorUnavailableReason.TRANSPORT_FAILURE);
    assertThat(((TutorOutcome.Unavailable) outcome).reason().expected())
        .as("a transport failure is operational and must not be filed as an ordinary state")
        .isFalse();
    assertThat(outcomeCount("unavailable", "AI_TRANSPORT_FAILURE")).isEqualTo(1);
  }

  @Test
  @DisplayName("an open circuit is reported as CIRCUIT_OPEN")
  void anOpenCircuitDegrades() {
    TutorService service = serviceWith((request, deadline) -> {
      throw new AiUnavailableException("AI_CIRCUIT_OPEN", "open");
    });

    TutorOutcome outcome = service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");

    assertThat(((TutorOutcome.Unavailable) outcome).reason())
        .isEqualTo(TutorUnavailableReason.CIRCUIT_OPEN);
    assertThat(outcomeCount("unavailable", "AI_CIRCUIT_OPEN")).isEqualTo(1);
  }

  @Test
  @DisplayName("a saturated bulkhead is reported as BUSY, distinct from a broken dependency")
  void aSaturatedBulkheadDegrades() {
    TutorService service = serviceWith((request, deadline) -> {
      throw new AiUnavailableException("AI_BULKHEAD_FULL", "busy");
    });

    TutorOutcome outcome = service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");

    // Busy is not broken. Merging them would make load look like an outage on a dashboard.
    assertThat(((TutorOutcome.Unavailable) outcome).reason())
        .isEqualTo(TutorUnavailableReason.BUSY);
    assertThat(outcomeCount("unavailable", "AI_BULKHEAD_FULL")).isEqualTo(1);
  }

  @Test
  @DisplayName("an unknown skill is refused before the AI plane is contacted")
  void anUnknownSkillNeverReachesTheAiPlane() {
    AtomicInteger calls = new AtomicInteger();
    TutorService service = serviceWith((request, deadline) -> {
      calls.incrementAndGet();
      return null;
    });

    TutorOutcome outcome = service.explain("ref-1", "NO_SUCH_SKILL", "NEEDS_PRACTICE", "en-IN");

    assertThat(((TutorOutcome.Unavailable) outcome).reason())
        .isEqualTo(TutorUnavailableReason.UNKNOWN_SKILL);
    // A caller bug, not an AI failure, so it must not appear in the operational counters as one.
    assertThat(((TutorOutcome.Unavailable) outcome).reason().expected()).isTrue();
    assertThat(calls).hasValue(0);
  }

  @Test
  @DisplayName("the request carries the domain so the agent need not assume one")
  void theRequestCarriesResolvedDomainContext() {
    AtomicInteger calls = new AtomicInteger();
    TutorService service = serviceWith((request, deadline) -> {
      calls.incrementAndGet();
      assertThat(request.domainContext()).isNotNull();
      assertThat(request.domainContext().domainCode()).isEqualTo("KAFKA");
      assertThat(request.domainContext().domainType()).isEqualTo(DomainType.TECHNOLOGY);
      assertThat(request.constraints().deadlineMs()).isEqualTo(12_000);
      throw new AiUnavailableException("AI_TRANSPORT_FAILURE", "checked what we needed");
    });

    service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("every unavailability is distinguishable, and operational ones stay visible")
  void unavailabilityIsNeverAnonymous() {
    // The requirement this class exists for: an empty result must never silently stand in for an
    // operational failure. Each reason is reachable, each is distinct, and the expected/operational
    // split is what stops a live outage being filed alongside a configuration choice.
    for (TutorUnavailableReason reason : TutorUnavailableReason.values()) {
      assertThat(reason.code()).isNotBlank();
    }

    assertThat(java.util.Arrays.stream(TutorUnavailableReason.values())
        .filter(TutorUnavailableReason::expected)
        .map(TutorUnavailableReason::name))
        .as("only a configuration state and a caller bug are ordinary; the rest need attention")
        .containsExactlyInAnyOrder("NOT_CONFIGURED", "UNKNOWN_SKILL");

    assertThat(java.util.Arrays.stream(TutorUnavailableReason.values())
        .filter(reason -> !reason.expected())
        .map(TutorUnavailableReason::name))
        .containsExactlyInAnyOrder("CIRCUIT_OPEN", "BUSY", "TRANSPORT_FAILURE", "DEADLINE_EXCEEDED");
  }

  @Test
  @DisplayName("an unrecognised failure code is treated as operational, not as expected")
  void anUnknownCodeStaysVisible() {
    // The safe default is the one that stays on the dashboard. A code nobody mapped must not
    // quietly become an ordinary state.
    assertThat(TutorUnavailableReason.fromCode("SOMETHING_NOBODY_MAPPED"))
        .isEqualTo(TutorUnavailableReason.TRANSPORT_FAILURE);
    assertThat(TutorUnavailableReason.fromCode("SOMETHING_NOBODY_MAPPED").expected()).isFalse();
  }

  @Test
  @DisplayName("an unconfigured AI plane is an ordinary state, not an incident")
  void anUnconfiguredPlaneIsExpected() {
    TutorService service = serviceWith(new AiClientConfiguration.UnconfiguredTutorPort());

    TutorOutcome outcome = service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");

    assertThat(((TutorOutcome.Unavailable) outcome).reason())
        .isEqualTo(TutorUnavailableReason.NOT_CONFIGURED);
    assertThat(((TutorOutcome.Unavailable) outcome).reason().expected()).isTrue();
    assertThat(outcomeCount("unavailable", "AI_NOT_CONFIGURED")).isEqualTo(1);
  }

  @Test
  @DisplayName("a successful call is counted separately from every failure")
  void successIsCountedSeparately() {
    TutorService service = serviceWith((request, deadline) -> proposal());

    TutorOutcome outcome = service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");

    assertThat(outcome.hasProposal()).isTrue();
    assertThat(outcomeCount("proposed", "none")).isEqualTo(1);
  }

  // -- no open DB transaction crosses the AI call ----------------------------------------------------

  @Test
  @DisplayName("the adapter refuses to call the AI plane inside a transaction")
  void theAdapterRefusesToRunInsideATransaction() {
    RamalsAiTutorClient client = new RamalsAiTutorClient(
        RestClient.create("http://localhost:1"),
        new AiCallGuard(3, Duration.ofSeconds(30), 4, Instant::now));

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.requestTutorResponse(request(), 12_000))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must not run inside a database transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  @DisplayName("the service holds no transaction when it calls the port")
  void theServiceCallsTheAiPlaneWithNoTransactionOpen() {
    // Observed at the moment of the call rather than inferred from the absence of an annotation:
    // a @Transactional added to a caller further up would not change the annotation on TutorService
    // but would very much change this.
    AtomicInteger observed = new AtomicInteger(-1);
    TutorService service = serviceWith((request, deadline) -> {
      observed.set(TransactionSynchronizationManager.isActualTransactionActive() ? 1 : 0);
      throw new AiUnavailableException("AI_TRANSPORT_FAILURE", "done looking");
    });

    service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");

    assertThat(observed).hasValue(0);
  }

  @Test
  @DisplayName("TutorService declares no transactional boundary of its own")
  void theServiceIsNotAnnotatedTransactional() {
    // Belt and braces alongside the runtime assertion: this one fails in review-time terms, at the
    // moment somebody adds the annotation, rather than when a connection pool runs dry.
    boolean annotated = java.util.Arrays.stream(TutorService.class.getDeclaredMethods())
        .anyMatch(method -> method.isAnnotationPresent(
            org.springframework.transaction.annotation.Transactional.class));

    assertThat(annotated)
        .as("an AI call inside a transaction holds a database connection for the length of a "
            + "model call; the deterministic core needs those connections")
        .isFalse();
    assertThat(TutorService.class.isAnnotationPresent(
        org.springframework.transaction.annotation.Transactional.class)).isFalse();
  }

  // -- transport failures normalize ------------------------------------------------------------------

  @Test
  @DisplayName("a transport failure becomes AiUnavailableException without leaking detail")
  void transportFailuresAreNormalized() {
    RamalsAiTutorClient client = new RamalsAiTutorClient(
        // Port 1 refuses immediately, so this exercises a real connection failure rather than a mock.
        RestClient.create("http://127.0.0.1:1"),
        new AiCallGuard(3, Duration.ofSeconds(30), 4, Instant::now));

    assertThatThrownBy(() -> client.requestTutorResponse(request(), 12_000))
        .isInstanceOf(AiUnavailableException.class)
        // A transport error can carry a host, a URL or a response body; none belong on a learner's
        // screen, so the message the learner may see is written here rather than propagated.
        .hasMessageNotContaining("127.0.0.1")
        .hasMessage("The tutoring service could not be reached.");
  }

  @Test
  @DisplayName("an exhausted deadline refuses before any connection is attempted")
  void anExhaustedDeadlineRefusesImmediately() {
    RamalsAiTutorClient client = new RamalsAiTutorClient(
        RestClient.create("http://127.0.0.1:1"),
        new AiCallGuard(3, Duration.ofSeconds(30), 4, Instant::now));

    assertThatThrownBy(() -> client.requestTutorResponse(request(), 0))
        .isInstanceOf(AiUnavailableException.class)
        .extracting(failure -> ((AiUnavailableException) failure).code())
        .isEqualTo("AI_DEADLINE_EXCEEDED");
  }

  private static io.ramals.learningplatform.ai.contract.AiProposalEnvelope proposal() {
    return new io.ramals.learningplatform.ai.contract.AiProposalEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION,
        "01920000-0000-7000-8000-0000000000c1",
        io.ramals.learningplatform.ai.contract.AgentType.TUTOR,
        "TUTOR_AGENT_V1",
        "TUTOR_PROMPT_V1",
        "tutor-default",
        io.ramals.learningplatform.ai.contract.TrustLevel.NON_AUTHORITATIVE,
        null, null, java.util.Map.of("responseType", "EXPLAIN"), null, null);
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION,
        "01920000-0000-7000-8000-0000000000a1",
        "01920000-0000-7000-8000-0000000000b1",
        new io.ramals.learningplatform.ai.contract.LearnerRef("ref-1", "en-IN"),
        new io.ramals.learningplatform.ai.contract.LearningContext(
            SKILL, null, null, "NEEDS_PRACTICE", null),
        new DomainContext("KAFKA", DomainType.TECHNOLOGY, "v1"),
        null,
        new io.ramals.learningplatform.ai.contract.Constraints(
            io.ramals.learningplatform.ai.contract.InteractionClass.INTERACTIVE_AI,
            12_000, null, null, null),
        "EXPLAIN");
  }
}
