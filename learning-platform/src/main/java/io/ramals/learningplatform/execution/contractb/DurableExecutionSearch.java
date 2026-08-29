package io.ramals.learningplatform.execution.contractb;

import java.util.List;

/**
 * What an enumeration search established about one {@code custom_id}.
 *
 * <p>Four outcomes, not three, and the fourth is the one the design turns on. Anthropic's batch list
 * carries no {@code custom_id}, so correlation means opening each candidate's results — and a batch
 * that has not ended has no results to open. Such a candidate is <em>uninspectable</em>, which is a
 * different answer from <em>does not match</em>: reporting {@link Outcome#ZERO} while one exists
 * would assert that no orphan exists at the moment one is most likely to be running (M2-ADR-020 §2).
 *
 * <p>The accounting fields are not diagnostics. A caller deciding whether to believe {@code ZERO}
 * needs to know the search finished, and one deciding whether to retry needs to know why it did not.
 *
 * @param uninspectable candidates inside the window that could not be correlated at all
 * @param limitReached which bound stopped the search — {@code pages} or {@code inspections} — or null
 * @param excluded candidates skipped because an earlier search already proved they do not carry the
 *     key, counted as covered rather than as uninspectable (M2-ADR-020 §3.1)
 * @param newlyExcluded batches this search has just proved do not carry the key, for the caller to
 *     record durably — ended and fully streamed only, never an uninspectable one
 */
public record DurableExecutionSearch(
    Outcome outcome,
    List<DiscoveredExecution> matches,
    int batchesListed,
    int batchesInspected,
    int uninspectable,
    int pagesFetched,
    String limitReached,
    int excluded,
    List<String> newlyExcluded) {

  public DurableExecutionSearch {
    matches = List.copyOf(matches);
    newlyExcluded = List.copyOf(newlyExcluded);
  }

  /**
   * The shape before M2-ADR-020 §3.1, for callers with nothing memoised.
   *
   * <p>Kept so that a search with no exclusions reads as one, rather than as a search with two
   * empty accounting fields bolted on.
   */
  public DurableExecutionSearch(Outcome outcome, List<DiscoveredExecution> matches,
      int batchesListed, int batchesInspected, int uninspectable, int pagesFetched,
      String limitReached) {
    this(outcome, matches, batchesListed, batchesInspected, uninspectable, pagesFetched,
        limitReached, 0, List.of());
  }

  /** What the search actually established. */
  public enum Outcome {
    /** Every candidate in the window was inspected; none carried the key. Conclusive. */
    ZERO,
    /** Exactly one provider execution carried it. */
    ONE,
    /** More than one did — the duplicate the Definition of Done exists to surface. */
    MULTIPLE,
    /** The search did not finish. Not an error, and not an answer: try again later. */
    INCONCLUSIVE;

    static Outcome of(String value) {
      try {
        return valueOf(value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        // An outcome this build cannot interpret must never be read as ZERO. Failing closed here
        // keeps a future provider-side value from being mistaken for "no orphan exists".
        return INCONCLUSIVE;
      }
    }
  }

  /** Whether the search reached a conclusion that may be acted on terminally. */
  public boolean conclusive() {
    return outcome != Outcome.INCONCLUSIVE;
  }
}
