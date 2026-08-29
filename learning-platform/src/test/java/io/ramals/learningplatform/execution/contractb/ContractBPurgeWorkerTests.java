package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The scheduler that finally runs the retention sweep.
 *
 * <p>The ceiling had a database function, a constraint, a grant and an alert, and nothing that ran
 * it. M2-ADR-018 §10 makes results outliving the ceiling a governance failure rather than a backlog,
 * and a rule whose only enforcement is someone remembering to run {@code psql} fails that rule as
 * surely as one that errors — just later, and more quietly.
 *
 * <p>What the sweep itself removes and preserves is covered against real PostgreSQL by
 * {@code ContractBPersistenceIntegrationTests} 8a–8f, including that it takes only terminal results
 * past the window and that everything M2-ADR-019 §1 retains survives it. These tests are about the
 * worker around it: that it is bounded, harmless, correlated, and cannot take the scheduler down.
 */
class ContractBPurgeWorkerTests {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  /** Records the bounds it was asked for, and can be made to fail. */
  private static class RecordingPurge extends ContractBResultPurge {

    private final List<int[]> sweeps = new ArrayList<>();
    private final List<String> correlation = new ArrayList<>();
    private RuntimeException failure;
    private int removed;

    RecordingPurge() {
      super(null);
    }

    @Override
    public int sweep(int retentionDays, int batchLimit) {
      sweeps.add(new int[] {retentionDays, batchLimit});
      correlation.add(MDC.get("interactionId"));
      if (failure != null) {
        throw failure;
      }
      return removed;
    }
  }

  private static ContractBProperties properties(int retentionDays, int batchSize) {
    ContractBProperties properties = new ContractBProperties();
    properties.getPurge().setRetentionDays(retentionDays);
    properties.getPurge().setBatchSize(batchSize);
    return properties;
  }

  @Test
  @DisplayName("the sweep runs with the configured window and batch bound")
  void theSweepIsBounded() {
    RecordingPurge purge = new RecordingPurge();

    new ContractBPurgeWorker(purge, properties(30, 250)).sweep();

    // The bound is what makes a scheduled delete safe to leave running: an unbounded sweep over an
    // unexpected backlog is one long transaction competing with live traffic.
    assertThat(purge.sweeps).hasSize(1);
    assertThat(purge.sweeps.get(0)).containsExactly(30, 250);
  }

  @Test
  @DisplayName("the defaults sweep at the ceiling, in bounded batches")
  void theDefaultsAreTheCeiling() {
    ContractBProperties.Purge defaults = new ContractBProperties().getPurge();

    // Thirty days is the ceiling itself, chosen against the provider's own 29-day retention. The
    // database function refuses anything above it, so this default cannot widen retention.
    assertThat(defaults.getRetentionDays()).isEqualTo(ContractBResultPurge.CEILING_DAYS);
    assertThat(defaults.getBatchSize()).isEqualTo(500);
    assertThat(defaults.getIntervalMs()).isEqualTo(21_600_000);
  }

  @Test
  @DisplayName("a sweep that removes nothing is the ordinary steady state, not a problem")
  void removingNothingIsNormal() {
    RecordingPurge purge = new RecordingPurge();
    purge.removed = 0;

    assertThatCode(() -> new ContractBPurgeWorker(purge, properties(30, 500)).sweep())
        .doesNotThrowAnyException();

    // Harmless when nothing qualifies -- which is every run until Contract B produces a result that
    // reaches the ceiling unadopted.
    assertThat(purge.sweeps).hasSize(1);
  }

  @Test
  @DisplayName("a failing sweep does not take the scheduler down with it")
  void aFailingSweepDoesNotKillTheScheduler() {
    RecordingPurge purge = new RecordingPurge();
    purge.failure = new DataAccessResourceFailureException("the database is unreachable");
    ContractBPurgeWorker worker = new ContractBPurgeWorker(purge, properties(30, 500));

    assertThatCode(worker::sweep).doesNotThrowAnyException();
    worker.sweep();

    // ContractBResultPurge has already logged the governance-worded ERROR; that is the alert. Letting
    // it escape here would kill the only thing that can enforce the ceiling, which is the opposite of
    // what a retention control should do when it fails.
    assertThat(purge.sweeps).hasSize(2);
  }

  @Test
  @DisplayName("each run is correlated, and leaves the scheduler thread clean")
  void eachRunIsCorrelatedAndLeavesNothingBehind() {
    RecordingPurge purge = new RecordingPurge();
    assertThat(MDC.get("interactionId")).as("precondition: a scheduler thread carries none").isNull();

    new ContractBPurgeWorker(purge, properties(30, 500)).sweep();

    // The sweep's own log lines are the retention evidence, and an uncorrelated governance event is
    // one nobody can join to anything else.
    String during = purge.correlation.get(0);
    assertThat(during).isNotNull().isNotBlank();
    assertThat(io.ramals.learningplatform.observability.UuidV7.isCanonical(during)).isTrue();
    // Scheduler threads are pooled: correlation left behind would attach to an unrelated job.
    assertThat(MDC.get("interactionId")).isNull();
  }

  @Test
  @DisplayName("two runs are correlated separately")
  void runsAreCorrelatedSeparately() {
    RecordingPurge purge = new RecordingPurge();
    ContractBPurgeWorker worker = new ContractBPurgeWorker(purge, properties(30, 500));

    worker.sweep();
    worker.sweep();

    assertThat(purge.correlation.get(0)).isNotEqualTo(purge.correlation.get(1));
  }
}
