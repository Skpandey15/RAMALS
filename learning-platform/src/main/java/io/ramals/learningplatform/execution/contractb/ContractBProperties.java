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
  private final Recovery recovery = new Recovery();

  public Recovery getRecovery() {
    return recovery;
  }

  /**
   * Lost-acknowledgement recovery bounds (M2-ADR-020 §1–§3).
   *
   * <p>Every value here trades search cost against the chance of missing an orphan, and the defaults
   * are chosen against the provider's own timings rather than picked to look round.
   */
  public static class Recovery {

    /**
     * How far either side of {@code submitted_at} the enumeration window reaches.
     *
     * <p>One hour, which is far wider than any plausible clock difference between RAMALS and the
     * provider or gap between the write-ahead claim and the request landing — and still narrow
     * enough that the candidate set stays small, which matters because every candidate costs a
     * results fetch.
     */
    private long searchSkewMs = 3_600_000;

    /**
     * How long an inconclusive search may keep being retried before the execution is recorded
     * indeterminate.
     *
     * <p>26 hours: the provider's 24-hour processing deadline plus a two-hour margin. Past it every
     * batch created in the window has necessarily ended, so a candidate that is still uninspectable
     * is unreadable for some other reason and waiting longer cannot help.
     */
    private long searchHorizonMs = 93_600_000;

    /**
     * The most result inspections one search may perform, whatever budget the pass still holds.
     *
     * <p>M2-ADR-020 §3's own bound, made explicit so it cannot be exceeded by configuring a large
     * pass budget. A window holding more than fifty candidates means the window or the traffic
     * assumption is wrong, and the honest answer is {@code INCONCLUSIVE} rather than a longer
     * search.
     */
    private int maxInspectionsPerSearch = 50;

    public int getMaxInspectionsPerSearch() {
      return maxInspectionsPerSearch;
    }

    public void setMaxInspectionsPerSearch(int maxInspectionsPerSearch) {
      this.maxInspectionsPerSearch = maxInspectionsPerSearch;
    }

    public long getSearchSkewMs() {
      return searchSkewMs;
    }

    public void setSearchSkewMs(long searchSkewMs) {
      this.searchSkewMs = searchSkewMs;
    }

    public long getSearchHorizonMs() {
      return searchHorizonMs;
    }

    public void setSearchHorizonMs(long searchHorizonMs) {
      this.searchHorizonMs = searchHorizonMs;
    }
  }

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

    /** The first backoff after a failed attempt. Doubles from here, up to {@link #maxBackoffMs}. */
    private long backoffMs = 30_000;

    /**
     * The ceiling on exponential backoff.
     *
     * <p>Fifteen minutes. Not decoration: doubling from thirty seconds passes the twenty-six-hour
     * search horizon in about a dozen attempts, so an uncapped backoff would stop retrying an
     * execution long before the horizon that is meant to end it, leaving it non-terminal and
     * unexamined. The cap keeps roughly a hundred attempts inside the horizon.
     */
    private long maxBackoffMs = 900_000;

    /**
     * The most random spread added to a backoff.
     *
     * <p>So that a fleet which backed off together does not return to the provider together. A
     * synchronised retry is how a rate limit becomes self-sustaining.
     */
    private long backoffJitterMs = 5_000;

    /**
     * Result inspections one reconciliation pass may spend across every orphan it handles
     * (M2-ADR-020 §3.2).
     *
     * <p>Per pass rather than per search, because per-search bounds do not compose: twenty orphans
     * at fifty inspections each authorises a thousand provider calls in one pass.
     *
     * <p><strong>Fifteen is a load-control default, not a guarantee.</strong> It does not prove the
     * provider's organization-wide limit cannot be exceeded — other traffic shares that limit, and
     * this budget is per process, so two workers are two budgets. It is sized to leave headroom: at
     * a thirty-second cadence it is roughly thirty inspections a minute against an observed limit
     * of fifty requests a minute.
     */
    private int inspectionBudgetPerPass = 15;

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

    public long getMaxBackoffMs() {
      return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
      this.maxBackoffMs = maxBackoffMs;
    }

    public long getBackoffJitterMs() {
      return backoffJitterMs;
    }

    public void setBackoffJitterMs(long backoffJitterMs) {
      this.backoffJitterMs = backoffJitterMs;
    }

    public int getInspectionBudgetPerPass() {
      return inspectionBudgetPerPass;
    }

    public void setInspectionBudgetPerPass(int inspectionBudgetPerPass) {
      this.inspectionBudgetPerPass = inspectionBudgetPerPass;
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
