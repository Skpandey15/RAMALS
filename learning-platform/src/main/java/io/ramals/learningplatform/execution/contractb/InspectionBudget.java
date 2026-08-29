package io.ramals.learningplatform.execution.contractb;

/**
 * How many provider result inspections one reconciliation pass may still spend (M2-ADR-020 §3.2).
 *
 * <p>Per pass rather than per search, because per-search bounds do not compose. A pass leasing
 * twenty orphans at fifty inspections each authorises a thousand provider calls — the same unbounded
 * behaviour a single search's bound exists to prevent, reintroduced by the loop around it. One
 * budget shared across the pass is what actually bounds the cost.
 *
 * <p>Deliberately mutable and deliberately not thread-safe. It belongs to one pass on one thread and
 * is spent as that pass proceeds; making it shareable would invite a second worker to spend it,
 * which is exactly the coordination this design does not claim to have. §7 of the ADR records that
 * the budget is per process.
 *
 * <p>Running out is not a failure. A search that stops for budget reports {@code INCONCLUSIVE},
 * which already means "try again later" — and because a bounded search resumes rather than restarts
 * (the durable memo, §3.1), the next pass continues where this one stopped.
 */
public final class InspectionBudget {

  private int remaining;

  private InspectionBudget(int remaining) {
    this.remaining = Math.max(0, remaining);
  }

  /** A budget of {@code inspections}, floored at zero. */
  public static InspectionBudget of(int inspections) {
    return new InspectionBudget(inspections);
  }

  public int remaining() {
    return remaining;
  }

  public boolean exhausted() {
    return remaining <= 0;
  }

  /**
   * Records inspections actually performed.
   *
   * <p>Floored at zero rather than allowed to go negative: a provider that reported inspecting more
   * than it was allowed is a reason to stop spending, not a reason to owe the next pass a debt.
   */
  public void spend(int inspections) {
    remaining = Math.max(0, remaining - Math.max(0, inspections));
  }
}
