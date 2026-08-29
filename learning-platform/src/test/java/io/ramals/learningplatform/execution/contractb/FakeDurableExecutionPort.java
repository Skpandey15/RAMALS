package io.ramals.learningplatform.execution.contractb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scriptable provider plane, for tests only.
 *
 * <p>Counts submissions. That counter is the point of the class: "exactly one provider submission
 * per owned durable execution" is a claim about how many times the provider was called, and the only
 * way to test it is to count. A fake that merely returned the right answers would let a double
 * submission pass unnoticed.
 *
 * <p>Every failure mode the real client can produce is scriptable here, including the one that
 * matters most — an ambiguous submission, which is neither an acceptance nor a refusal.
 */
public final class FakeDurableExecutionPort implements DurableExecutionPort {

  /** Every submission attempt, in order. Assert on its size, not just its content. */
  final List<String> submissions = new ArrayList<>();

  final AtomicInteger statusCalls = new AtomicInteger();
  final AtomicInteger resultCalls = new AtomicInteger();

  private String providerExecutionId = "msgbatch_fake0000000001";
  private RuntimeException submitFailure;
  private boolean acknowledgeWithoutIdentity;
  private String state = "RUNNING";
  private String nativeStatus = "in_progress";
  private DurableResultRecord result;
  private RuntimeException statusFailure;
  private RuntimeException resultFailure;

  private List<FakeBatch> window;

  /** What the last search was told had already been ruled out. Assert on it. */
  List<String> lastExcludeIds = List.of();

  /** The inspection allowance the last search was given. */
  int lastMaxInspections = -1;

  FakeDurableExecutionPort providerExecutionId(String id) {
    this.providerExecutionId = id;
    return this;
  }

  /** The submission neither succeeds nor definitely fails. */
  FakeDurableExecutionPort ambiguousSubmit() {
    this.submitFailure = new DurableSubmissionAmbiguousException("test", "scripted ambiguity");
    return this;
  }

  /** The far side chose a status: it decided against the request and created nothing. */
  FakeDurableExecutionPort refusedSubmit() {
    this.submitFailure = new DurableExecutionRefusedException("test", 400);
    return this;
  }

  /**
   * A failure nobody classified, of the kind a mapping bug or an interceptor throws.
   *
   * <p>Deliberately a plain {@code RuntimeException} and deliberately thrown from the same place a
   * real one would be: after the call has begun, where it cannot prove the provider created
   * nothing.
   */
  FakeDurableExecutionPort unclassifiedSubmitFailure() {
    this.submitFailure = new IllegalStateException("scripted unclassified failure");
    return this;
  }

  /** A 2xx that carries no execution identity: an acknowledgement in form only. */
  FakeDurableExecutionPort acknowledgeWithoutIdentity() {
    this.acknowledgeWithoutIdentity = true;
    return this;
  }

  FakeDurableExecutionPort state(String state, String nativeStatus) {
    this.state = state;
    this.nativeStatus = nativeStatus;
    return this;
  }

  FakeDurableExecutionPort statusUnavailable() {
    this.statusFailure = new IllegalStateException("scripted status outage");
    return this;
  }

  FakeDurableExecutionPort resultUnavailable() {
    this.resultFailure = new IllegalStateException("scripted result outage");
    return this;
  }

  FakeDurableExecutionPort succeedsWith(String text, String customId) {
    // The adapter's own word for an ended batch. It used to say "SUCCEEDED" here, which the adapter
    // never emits -- so the fake agreed with an assumption and the mismatch survived 51 green tests
    // until a real provider run hit it.
    this.state = "RESULT_AVAILABLE";
    this.nativeStatus = "ended";
    this.result = new DurableResultRecord(providerExecutionId, "succeeded", customId, text,
        16, 4, "msg_fake01", null);
    return this;
  }

  FakeDurableExecutionPort recordOutcome(String outcome, String customId) {
    this.state = "RESULT_AVAILABLE";
    this.nativeStatus = "ended";
    this.result = new DurableResultRecord(providerExecutionId, outcome, customId, null,
        16, 0, null, "provider_error");
    return this;
  }

  @Override
  public DurableSubmissionAck submit(DurableSubmissionCommand command) {
    submissions.add(command.requestId());
    if (submitFailure != null) {
      throw submitFailure;
    }
    return new DurableSubmissionAck(
        acknowledgeWithoutIdentity ? null : providerExecutionId,
        "ACCEPTED", command.idempotencyKey(), null, null);
  }

  /** Scripted enumeration outcome. Defaults to a conclusive empty search. */
  private DurableExecutionSearch search =
      new DurableExecutionSearch(DurableExecutionSearch.Outcome.ZERO, List.of(), 0, 0, 0, 1, null);

  private RuntimeException searchFailure;

  final AtomicInteger searchCalls = new AtomicInteger();

  FakeDurableExecutionPort searchFinds(DurableExecutionSearch.Outcome outcome,
      DiscoveredExecution... matches) {
    this.search = new DurableExecutionSearch(outcome, List.of(matches), matches.length,
        matches.length, outcome == DurableExecutionSearch.Outcome.INCONCLUSIVE ? 1 : 0, 1, null);
    return this;
  }

  FakeDurableExecutionPort searchUnavailable() {
    this.searchFailure = new IllegalStateException("scripted enumeration outage");
    return this;
  }

  /**
   * A batch sitting in the correlation window.
   *
   * @param ended whether its results can be read at all — an unfinished batch is uninspectable,
   *     which is emphatically not "does not carry the key"
   */
  record FakeBatch(String id, boolean ended, boolean carriesKey) {

    static FakeBatch ended(String id) {
      return new FakeBatch(id, true, false);
    }

    static FakeBatch carrying(String id) {
      return new FakeBatch(id, true, true);
    }

    static FakeBatch inProgress(String id) {
      return new FakeBatch(id, false, false);
    }
  }

  /**
   * Scripts a window of candidates instead of a canned outcome.
   *
   * <p>The fake then honours exclusions and the inspection budget the way the adapter does, which is
   * what makes a test about <em>cumulative</em> coverage mean anything: a canned outcome would
   * report {@code ZERO} however little was actually inspected, and the property under test is
   * precisely that it does not.
   *
   * <p>The outcome rules here deliberately mirror {@code anthropic_batches_adapter.py}. The Python
   * unit tests remain the authority on them — a fake that agreed with an assumption rather than with
   * the implementation is how W2 shipped three defects — and this exists to exercise the wiring
   * around them, not to re-decide them.
   */
  FakeDurableExecutionPort withWindow(FakeBatch... batches) {
    this.window = List.of(batches);
    return this;
  }

  /** The provider refuses because it is being asked too often. */
  FakeDurableExecutionPort searchRateLimited(Long retryAfterMillis) {
    this.searchFailure =
        new DurableExecutionRateLimitedException("scripted rate limit", retryAfterMillis);
    return this;
  }

  @Override
  public DurableExecutionSearch search(String customId, String from, String to,
      int maxInspections, java.util.Collection<String> excludeIds) {
    searchCalls.incrementAndGet();
    lastExcludeIds = List.copyOf(excludeIds);
    lastMaxInspections = maxInspections;
    if (searchFailure != null) {
      throw searchFailure;
    }
    if (window == null) {
      return search;
    }

    List<DiscoveredExecution> matches = new ArrayList<>();
    List<String> newlyExcluded = new ArrayList<>();
    int inspected = 0;
    int uninspectable = 0;
    int excluded = 0;
    String limitReached = null;

    for (FakeBatch batch : window) {
      if (lastExcludeIds.contains(batch.id())) {
        // Covered by an earlier search, not re-opened. Counted as coverage, never as a candidate
        // this search could not read.
        excluded++;
        continue;
      }
      if (!batch.ended()) {
        uninspectable++;
        continue;
      }
      if (inspected >= maxInspections) {
        limitReached = "inspections";
        break;
      }
      inspected++;
      if (batch.carriesKey()) {
        matches.add(new DiscoveredExecution(batch.id(), customId, "succeeded", 10, 20, 0,
            null, null, "ended"));
      } else {
        // Ended, read to the end, key absent. The only case that may be remembered.
        newlyExcluded.add(batch.id());
      }
    }

    boolean incomplete = uninspectable > 0 || limitReached != null;
    DurableExecutionSearch.Outcome outcome;
    if (matches.size() > 1) {
      outcome = DurableExecutionSearch.Outcome.MULTIPLE;
    } else if (incomplete) {
      outcome = DurableExecutionSearch.Outcome.INCONCLUSIVE;
    } else if (matches.size() == 1) {
      outcome = DurableExecutionSearch.Outcome.ONE;
    } else {
      outcome = DurableExecutionSearch.Outcome.ZERO;
    }

    return new DurableExecutionSearch(outcome, matches, window.size(), inspected, uninspectable,
        1, limitReached, excluded, newlyExcluded);
  }

  @Override
  public DurableStatusSnapshot status(String id) {
    statusCalls.incrementAndGet();
    if (statusFailure != null) {
      throw statusFailure;
    }
    return new DurableStatusSnapshot(id, state, nativeStatus, result != null, null);
  }

  @Override
  public DurableResultRecord result(String id, String customId) {
    resultCalls.incrementAndGet();
    if (resultFailure != null) {
      throw resultFailure;
    }
    if (result == null) {
      throw new IllegalStateException("no scripted result");
    }
    return result;
  }
}
