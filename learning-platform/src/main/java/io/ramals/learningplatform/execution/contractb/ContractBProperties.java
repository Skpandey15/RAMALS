package io.ramals.learningplatform.execution.contractb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Contract B configuration. Everything off by default.
 *
 * <p>Two separate switches rather than one, because they authorise different things and should be
 * turned on at different times. {@code enabled} is the public route — the learner-facing path that
 * commissions a durable execution — and stays off until crash/recovery qualification passes.
 * {@code reconciliation.enabled} is the background worker, which is what qualification itself needs
 * in order to run.
 *
 * <p>A single flag would force the choice between qualifying nothing and exposing an unqualified
 * route to learners.
 */
@ConfigurationProperties(prefix = "ramals.contract-b")
public class ContractBProperties {

  /**
   * Whether the public Contract B route is served.
   *
   * <p>False, and it must stay false until the lost-acknowledgement crash matrix has been executed
   * against a real provider. The Definition of Done requires that qualification explicitly, and no
   * amount of passing unit tests substitutes for it.
   */
  private boolean enabled = false;

  private final Reconciliation reconciliation = new Reconciliation();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Reconciliation getReconciliation() {
    return reconciliation;
  }

  /** The background worker's bounds. */
  public static class Reconciliation {

    /** Whether the worker runs. Off by default; it calls a paid provider. */
    private boolean enabled = false;

    /**
     * How long a lease is held.
     *
     * <p>Longer than one provider round trip and far shorter than a deployment, so a process that
     * dies mid-reconciliation is picked up in seconds rather than after an operator notices.
     */
    private long leaseMs = 60_000;

    /** How long a non-terminal execution waits before the next attempt. */
    private long backoffMs = 30_000;

    /** Executions per pass. Bounded so one pass cannot hold a long transaction or a long deadline. */
    private int batchSize = 20;

    /**
     * How long a sent-but-unacknowledged submission is left alone before it is recorded
     * {@code INDETERMINATE}.
     *
     * <p>Five minutes, chosen against one provider round trip rather than plucked. A submission in
     * progress right now is indistinguishable from one whose acknowledgement was lost, so the only
     * thing separating them is elapsed time — too short and the sweep terminates executions that are
     * about to succeed, which is worse than leaving them briefly ambiguous.
     */
    private long unacknowledgedGraceMs = 300_000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getLeaseMs() {
      return leaseMs;
    }

    public void setLeaseMs(long leaseMs) {
      this.leaseMs = leaseMs;
    }

    public long getBackoffMs() {
      return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
      this.backoffMs = backoffMs;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public long getUnacknowledgedGraceMs() {
      return unacknowledgedGraceMs;
    }

    public void setUnacknowledgedGraceMs(long unacknowledgedGraceMs) {
      this.unacknowledgedGraceMs = unacknowledgedGraceMs;
    }
  }
}
