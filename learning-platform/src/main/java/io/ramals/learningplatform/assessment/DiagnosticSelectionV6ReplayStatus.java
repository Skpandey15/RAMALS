package io.ramals.learningplatform.assessment;

/**
 * M2-ADR-034 Amendment 4: the outcome of one {@link DiagnosticSelectionV6ReplayService#replay} call.
 */
public enum DiagnosticSelectionV6ReplayStatus {

  /** Either no {@code core.diagnostic_selection_replay_input} snapshot exists for this destination
   * attempt (it predates Amendment 4), or the destination attempt was never governed by {@code
   * DIAGNOSTIC_SELECTION_V6} at all and (correctly) has no snapshot. These two cases are
   * deliberately indistinguishable from outside; either way, exact replay is not available, and
   * nothing here is ever inferred from {@code created_at} or current state. Contrast {@link
   * #INTEGRITY_FAILURE}'s own non-V6-attempt-with-a-snapshot case, which is not this. */
  NOT_AVAILABLE,

  /** A {@code core.diagnostic_selection_replay_input} snapshot exists, but its {@code
   * snapshot_contract_version} is not the one this build of {@code DiagnosticSelectionV6ReplayService}
   * knows how to interpret ({@link DiagnosticSelectionReplayInputRepository#SNAPSHOT_CONTRACT_VERSION}).
   * Never replayed under a guessed-at compatibility mapping -- a future {@code _V2} contract must be
   * interpreted by its own, explicitly updated replay logic, never by silently reusing {@code _V1}
   * semantics against a shape it was not designed for. */
  UNSUPPORTED_SNAPSHOT_VERSION,

  /** The recomputed decision (Step 1/Step 2 applied verbatim to the persisted working set) matches
   * every persisted audit field -- {@code activated}/{@code fallback_reason}, the hypothesis/
   * candidate/participation counts, and {@code step1_status}/{@code step2_status} -- and the
   * historical final probe (whether {@code V6} itself activated and chose it, or {@code V6} fell
   * back and {@code V5}'s own resolution chose it) matches {@code core.diagnostic_probe_provenance}. */
  VERIFIED,

  /** Some persisted fact and this replay disagree, and it is never silently swallowed. Covers:
   * the recomputed decision diverging from persisted {@code activated}/{@code fallback_reason} or
   * any other persisted audit count/status; a recomputed {@code V6}-activated selection with no
   * matching {@code core.diagnostic_probe_provenance} row; a fallback decision that could not
   * possibly have produced a hypothesis-driven probe (no source attempt, or zero actionable
   * hypotheses) yet provenance records one anyway; more than one provenance row for one attempt
   * (a packet-quota violation); and a {@code core.diagnostic_selection_replay_input} snapshot that
   * exists for an attempt {@code DIAGNOSTIC_SELECTION_V6} never governed -- a contradictory
   * persisted state that must never be silently accepted as replayable. */
  INTEGRITY_FAILURE
}
