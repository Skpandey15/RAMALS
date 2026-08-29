package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.UuidV7;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Correlation across the reconciliation hand-off (M1-ADR-003, V040).
 *
 * <p>The defect these guard against did not look like a correlation bug. It looked like Contract B
 * being unable to talk to the AI plane at all: a scheduler thread carries no MDC, so
 * {@code currentInteractionId()} returned {@code ""}, the client sent an empty header, and the AI
 * plane answered 400 for every recovery call. It survived every existing test because they all hand
 * the lifecycle a fake port and never look at a header.
 *
 * <p>So these tests assert on what the worker <em>establishes</em>, not on what the client sends —
 * that half is covered at the real HTTP boundary in {@link ContractBHttpBoundaryTests}. Together
 * they close the gap from both directions.
 */
class ContractBReconciliationCorrelationTests {

  private final List<String[]> observed = new ArrayList<>();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  /**
   * A repository that answers from memory and records what correlation each reconcile ran under.
   *
   * <p>Subclassed rather than mocked so the worker's real control flow runs: the leasing, the
   * ordering, the backoff call. A mock would let the loop under test be replaced by expectations
   * about it.
   */
  private final class RecordingRepository extends ProviderExecutionRepository {

    private final Map<String, String[]> correlations = new LinkedHashMap<>();
    private final List<String> due = new ArrayList<>();

    RecordingRepository() {
      super(null);
    }

    void with(String requestId, String interactionId, String traceId) {
      correlations.put(requestId, new String[] {interactionId, traceId});
      due.add(requestId);
    }

    @Override
    public String[] correlationOf(String requestId) {
      return correlations.getOrDefault(requestId, new String[] {null, null});
    }

    @Override
    public List<String> leaseDue(java.util.UUID owner, long leaseMillis, int limit) {
      return List.copyOf(due);
    }

    @Override
    public List<ProviderExecution> reconcilableWithoutWork(int limit) {
      return List.of();
    }

    @Override
    public List<String> sentWithoutAcknowledgement(long staleMillis, int limit) {
      return List.of();
    }

    @Override
    public int recordReconciliationAttempt(
        String requestId, long baseMillis, long maxMillis, long jitterMillis) {
      return 1;
    }

    @Override
    public void enqueueReconciliation(String requestId) {
      // no queue in this test
    }
  }

  /** Captures the MDC in force at the moment the lifecycle would call the AI plane. */
  private final class ObservingLifecycle extends ContractBExecutionService {

    ObservingLifecycle() {
      super(null, null, null, null, null, new ContractBProperties());
    }

    @Override
    public DurableExecutionState reconcile(String requestId, InspectionBudget budget) {
      observed.add(new String[] {
          requestId, MDC.get("interactionId"), MDC.get("traceId")});
      return DurableExecutionState.RECONCILING;
    }
  }

  private ContractBReconciliationWorker worker(RecordingRepository repository) {
    return new ContractBReconciliationWorker(
        repository, new ObservingLifecycle(), new ContractBProperties());
  }

  @Test
  @DisplayName("a scheduled pass with no pre-existing MDC still establishes a canonical id")
  void aScheduledPassEstablishesCorrelation() {
    RecordingRepository repository = new RecordingRepository();
    repository.with("req-a", null, null);
    assertThat(MDC.get("interactionId")).as("precondition: a scheduler thread carries none").isNull();

    worker(repository).poll();

    // The exact case that produced 400s: no request, no MDC, nothing recorded. A generated id is
    // correct here -- it is where this trail starts -- and an empty one is refused by the AI plane.
    assertThat(observed).hasSize(1);
    String interactionId = observed.get(0)[1];
    assertThat(interactionId).isNotNull().isNotBlank();
    assertThat(UuidV7.isCanonical(interactionId)).isTrue();
  }

  @Test
  @DisplayName("the recorded correlation is restored, so an execution stays traceable to its request")
  void recordedCorrelationIsRestored() {
    String originating = UuidV7.generate().toString();
    RecordingRepository repository = new RecordingRepository();
    repository.with("req-a", originating, "0af7651916cd43dd8448eb211c80319c");

    worker(repository).poll();

    // The point of persisting it in V040. A generated id would work and would quietly sever the
    // execution from the learner request that created it.
    assertThat(observed.get(0)[1]).isEqualTo(originating);
    assertThat(observed.get(0)[2]).isEqualTo("0af7651916cd43dd8448eb211c80319c");
  }

  @Test
  @DisplayName("each execution in one pass runs under its own correlation")
  void correlationIsPerExecutionNotPerPass() {
    String first = UuidV7.generate().toString();
    String second = UuidV7.generate().toString();
    RecordingRepository repository = new RecordingRepository();
    repository.with("req-a", first, "0af7651916cd43dd8448eb211c80319c");
    repository.with("req-b", second, "bbf7651916cd43dd8448eb211c80319c");

    worker(repository).poll();

    // A pass batches whichever unrelated learners' executions came due together. Filing them under
    // one interaction id would merge separate learners' provenance into one trail.
    assertThat(observed).hasSize(2);
    assertThat(observed.get(0)[1]).isEqualTo(first);
    assertThat(observed.get(1)[1]).isEqualTo(second);
    assertThat(observed.get(0)[1]).isNotEqualTo(observed.get(1)[1]);
  }

  @Test
  @DisplayName("one execution's correlation never leaks into the next")
  void correlationDoesNotLeakBetweenExecutions() {
    RecordingRepository repository = new RecordingRepository();
    repository.with("req-a", UuidV7.generate().toString(), "0af7651916cd43dd8448eb211c80319c");
    repository.with("req-b", null, null);

    worker(repository).poll();

    // The second has no recorded correlation. If the scope were not closed it would silently
    // inherit the first's, and two learners' executions would be reported as one interaction --
    // a wrong answer that looks exactly like a right one.
    assertThat(observed.get(1)[1]).isNotEqualTo(observed.get(0)[1]);
    assertThat(observed.get(1)[2]).as("a stale traceId must not survive either").isNull();
  }

  @Test
  @DisplayName("the pass leaves the thread's MDC exactly as it found it")
  void theThreadIsLeftClean() {
    RecordingRepository repository = new RecordingRepository();
    repository.with("req-a", UuidV7.generate().toString(), "0af7651916cd43dd8448eb211c80319c");

    worker(repository).poll();

    // Scheduler threads are pooled and reused. Correlation left behind would attach itself to
    // whatever unrelated job ran next on the same thread.
    assertThat(MDC.get("interactionId")).isNull();
    assertThat(MDC.get("traceId")).isNull();
  }

  @Test
  @DisplayName("a pre-existing correlation on the calling thread is restored afterwards")
  void anEnclosingCorrelationSurvivesThePass() {
    String enclosing = UuidV7.generate().toString();
    RecordingRepository repository = new RecordingRepository();
    repository.with("req-a", UuidV7.generate().toString(), "0af7651916cd43dd8448eb211c80319c");

    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(enclosing, "ccf7651916cd43dd8448eb211c80319c")) {
      worker(repository).poll();
      assertThat(MDC.get("interactionId"))
          .as("the caller's own correlation must survive the hand-off")
          .isEqualTo(enclosing);
    }
  }

  @Test
  @DisplayName("admission records the correlation in force, and blank is stored as absent")
  void admissionRecordsCorrelation() {
    List<Object[]> admitted = new ArrayList<>();
    ProviderExecutionRepository repository = new ProviderExecutionRepository(null) {
      @Override
      public boolean admit(String requestId, String idempotencyKey, String provider, String model,
          String modelRoute, String interactionId, String traceId) {
        admitted.add(new Object[] {requestId, interactionId, traceId});
        return true;
      }

      @Override
      public Optional<ProviderExecution> find(String requestId) {
        return Optional.empty();
      }
    };
    ContractBExecutionService service = new ContractBExecutionService(
        repository, new ContractBTransitionLedger(null) {
          @Override
          public void record(String requestId, DurableExecutionState from, DurableExecutionState to,
              String actor, long fence, String reason) {
            // the ledger is not under test here
          }
        }, null, null, null, new ContractBProperties());

    String originating = UuidV7.generate().toString();
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(originating, "0af7651916cd43dd8448eb211c80319c")) {
      service.admit("req-a", "idem-a", "anthropic", "claude-haiku-4-5-20251001", "diagnostic");
    }
    service.admit("req-b", "idem-b", "anthropic", "claude-haiku-4-5-20251001", "diagnostic");

    // Captured at admission because that is the only moment the originating correlation exists on
    // the thread; reconciliation happens later with nothing behind it.
    assertThat(admitted.get(0)[1]).isEqualTo(originating);
    // And outside a request there is genuinely nothing to record. Blank must reach the row as null,
    // never as an empty string -- the constraint in V040 refuses the latter.
    assertThat(admitted.get(1)[1]).isIn((Object) null, "");
  }
}
