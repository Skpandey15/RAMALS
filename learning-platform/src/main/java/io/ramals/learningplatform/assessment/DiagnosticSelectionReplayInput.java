package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * M2-ADR-034 Amendment 4: the persisted {@code core.diagnostic_selection_replay_input} header row
 * for one {@code DIAGNOSTIC_SELECTION_V6} attempt -- authoritative decision-time provenance, never a
 * cache of a value replay could otherwise compute.
 *
 * @param sourceAttemptId WHICH source attempt {@code V6} actually used, fixed at decision time.
 *     {@code null} iff the original decision's {@code fallbackReason} was {@code NO_SOURCE_ATTEMPT}
 *     -- that persisted {@code null} is itself the authoritative fact. Replay must read this field
 *     directly and must never re-run {@code findMostRecentCompletedAttempt(...)} (or any equivalent
 *     "latest completed attempt" discovery) to rediscover it -- that lookup is itself time-sensitive
 *     and can return a different, later attempt once more time has passed.
 * @param snapshotContractVersion always {@link DiagnosticSelectionReplayInputRepository#SNAPSHOT_CONTRACT_VERSION}
 *     -- a dedicated identifier distinct from {@code DIAGNOSTIC_SELECTION_V6} (the selection policy)
 *     and from either frozen engine's own version string.
 * @param fallbackReason persisted as an audit/integrity value only -- replay always recomputes the
 *     activation/fallback outcome from the persisted working set and compares it against this field,
 *     rather than trusting it as the sole source of truth (Amendment 4 §T).
 */
public record DiagnosticSelectionReplayInput(
    UUID id,
    UUID destinationAttemptId,
    UUID sourceAttemptId,
    String snapshotContractVersion,
    int relationshipAuthorizedCount,
    int actionableHypothesisCount,
    int candidateProbeCount,
    int participatingHypothesisCount,
    String step1Status,
    String step2Status,
    boolean activated,
    V6FallbackReason fallbackReason) {
}
