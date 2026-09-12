package io.ramals.learningplatform.assessment;

/**
 * M2-ADR-034 Amendment 4: the outcome of one {@link DiagnosticSelectionV6ReplayService#replay} call.
 */
public enum DiagnosticSelectionV6ReplayStatus {

  /** No {@code core.diagnostic_selection_replay_input} snapshot exists for this destination
   * attempt -- either it predates Amendment 4, or {@code DIAGNOSTIC_SELECTION_V6} never ran for it.
   * The two cases are deliberately indistinguishable from outside; either way, exact replay is not
   * available, and nothing here is ever inferred from {@code created_at} or current state. */
  NOT_AVAILABLE,

  /** The recomputed decision (Step 1/Step 2 applied verbatim to the persisted working set) matches
   * the persisted {@code activated}/{@code fallback_reason} exactly, and -- when a probe was
   * selected -- the recomputed selection matches {@code core.diagnostic_probe_provenance}. */
  VERIFIED,

  /** The recomputed decision diverges from what was persisted at decision time, or the recomputed
   * selection does not match {@code core.diagnostic_probe_provenance}. This is never silently
   * swallowed -- a mismatch here means the persisted snapshot and this replay disagree about what
   * {@code DIAGNOSTIC_SELECTION_V6} would have chosen, which is always worth surfacing. */
  INTEGRITY_FAILURE
}
