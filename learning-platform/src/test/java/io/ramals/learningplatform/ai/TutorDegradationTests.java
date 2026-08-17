package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import java.time.Duration;
import java.time.Instant;
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

  private static TutorService serviceWith(TutorPort port) {
    return new TutorService(port, assembler());
  }

  // -- ramals-ai down does not block deterministic learning ------------------------------------------

  @Test
  @DisplayName("an unreachable AI plane yields an empty proposal, not an exception")
  void anUnreachableAiPlaneDegrades() {
    TutorService service = serviceWith((request, deadline) -> {
      throw new AiUnavailableException("AI_TRANSPORT_FAILURE", "unreachable");
    });

    Optional<AiProposalEnvelope> proposal = service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN");

    // Empty rather than thrown, so a caller has to go out of its way to turn a missing tutor into a
    // failed learner request.
    assertThat(proposal).isEmpty();
  }

  @Test
  @DisplayName("an open circuit yields an empty proposal")
  void anOpenCircuitDegrades() {
    TutorService service = serviceWith((request, deadline) -> {
      throw new AiUnavailableException("AI_CIRCUIT_OPEN", "open");
    });

    assertThat(service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN")).isEmpty();
  }

  @Test
  @DisplayName("a saturated bulkhead yields an empty proposal")
  void aSaturatedBulkheadDegrades() {
    TutorService service = serviceWith((request, deadline) -> {
      throw new AiUnavailableException("AI_BULKHEAD_FULL", "busy");
    });

    assertThat(service.explain("ref-1", SKILL, "NEEDS_PRACTICE", "en-IN")).isEmpty();
  }

  @Test
  @DisplayName("an unknown skill is refused before the AI plane is contacted")
  void anUnknownSkillNeverReachesTheAiPlane() {
    AtomicInteger calls = new AtomicInteger();
    TutorService service = serviceWith((request, deadline) -> {
      calls.incrementAndGet();
      return null;
    });

    assertThat(service.explain("ref-1", "NO_SUCH_SKILL", "NEEDS_PRACTICE", "en-IN")).isEmpty();
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
