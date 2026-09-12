package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateProbe;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2-ADR-034 Amendment 4: deterministic exact replay of one {@code DIAGNOSTIC_SELECTION_V6}
 * decision, from persisted provenance alone.
 *
 * <p><b>Persist WHICH source attempt was used; reconstruct WHAT that source attempt contained.</b>
 * This class never calls {@link AssessmentRepository#findMostRecentCompletedAttempt}, {@link
 * AssessmentRepository#findLearnerExposedLogicalItemIds}, or any other live-discovery/exposure
 * query -- every historical input it feeds back into {@link
 * HypothesisDiscriminationDiagnosticSelector#decideFromPersistedWorkingSet} comes from the
 * immutable {@code core.diagnostic_selection_replay_input} / {@code
 * core.diagnostic_selection_replay_candidate_probe} snapshot recorded at decision time (or, for
 * {@code NO_SOURCE_ATTEMPT}, from {@link HypothesisDiscriminationDiagnosticSelector#noSourceAttemptDecision}
 * directly, never by re-searching for a source attempt).
 *
 * <p>Persisted {@code activated}/{@code fallback_reason} are audit/integrity values only -- this
 * class always recomputes the decision and compares it against them, surfacing any divergence as
 * {@link DiagnosticSelectionV6ReplayStatus#INTEGRITY_FAILURE} rather than trusting the persisted
 * outcome. It never persists a discrimination score itself.
 */
@Service
public class DiagnosticSelectionV6ReplayService {

  private final AssessmentRepository repository;
  private final DiagnosticSelectionReplayInputRepository replayInputRepository;
  private final ProbeProvenanceRepository probeProvenanceRepository;
  private final HypothesisDiscriminationDiagnosticSelector selector;

  public DiagnosticSelectionV6ReplayService(
      AssessmentRepository repository,
      DiagnosticSelectionReplayInputRepository replayInputRepository,
      ProbeProvenanceRepository probeProvenanceRepository,
      HypothesisDiscriminationDiagnosticSelector selector) {
    this.repository = repository;
    this.replayInputRepository = replayInputRepository;
    this.probeProvenanceRepository = probeProvenanceRepository;
    this.selector = selector;
  }

  /**
   * Replays {@code destinationAttemptId}'s {@code DIAGNOSTIC_SELECTION_V6} decision exactly, or
   * reports {@link DiagnosticSelectionV6ReplayStatus#NOT_AVAILABLE} when no snapshot exists --
   * pre-Amendment-4 attempts and non-V6 attempts are deliberately indistinguishable from outside,
   * and neither is ever inferred from {@code created_at} or from the attempt's current state.
   *
   * @throws AttemptNotFoundException if {@code destinationAttemptId} does not exist
   */
  @Transactional(readOnly = true)
  public DiagnosticSelectionV6ReplayResult replay(UUID destinationAttemptId) {
    AssessmentAttempt destination = repository.findAttempt(destinationAttemptId)
        .orElseThrow(() -> new AttemptNotFoundException(String.valueOf(destinationAttemptId)));

    Optional<DiagnosticSelectionReplayInput> snapshotOpt =
        replayInputRepository.findByDestinationAttempt(destinationAttemptId);
    if (snapshotOpt.isEmpty()) {
      return DiagnosticSelectionV6ReplayResult.notAvailable(destinationAttemptId);
    }
    DiagnosticSelectionReplayInput snapshot = snapshotOpt.get();

    HypothesisDiscriminationDiagnosticSelector.Decision recomputed;
    if (snapshot.sourceAttemptId() == null) {
      // Amendment 4 §K: reproduced directly, never by re-running source-attempt discovery.
      recomputed = HypothesisDiscriminationDiagnosticSelector.noSourceAttemptDecision();
    } else {
      List<CandidateProbe> candidateProbes = replayInputRepository.findCandidateProbes(snapshot.id());
      List<DiagnosticHypothesis> actionableHypotheses =
          replayInputRepository.findActionableHypotheses(snapshot.id());
      // The destination version's full, unfiltered item pool -- immutable published item metadata,
      // never a learner's current unseen pool (see decideFromPersistedWorkingSet's own javadoc).
      List<AdaptiveEligibleItem> itemPool =
          repository.findAdaptiveEligibleItems(destination.assessmentVersionId());

      recomputed = selector.decideFromPersistedWorkingSet(
          snapshot.sourceAttemptId(), actionableHypotheses, candidateProbes,
          snapshot.relationshipAuthorizedCount(), itemPool);
    }

    return verify(destinationAttemptId, snapshot, recomputed);
  }

  private DiagnosticSelectionV6ReplayResult verify(
      UUID destinationAttemptId, DiagnosticSelectionReplayInput snapshot,
      HypothesisDiscriminationDiagnosticSelector.Decision recomputed) {
    if (recomputed.activated() != snapshot.activated()
        || !Objects.equals(recomputed.fallbackReason(), snapshot.fallbackReason())) {
      return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, recomputed,
          "recomputed activated=%s/fallbackReason=%s diverges from persisted activated=%s/fallbackReason=%s"
              .formatted(recomputed.activated(), recomputed.fallbackReason(), snapshot.activated(),
                  snapshot.fallbackReason()));
    }

    if (recomputed.activated()) {
      UUID selectedItemVersionId = recomputed.selection()
          .orElseThrow(() -> new IllegalStateException("activated Decision must carry a selection"))
          .chosenItemVersionId();
      boolean selectionMatchesProvenance = probeProvenanceRepository
          .findByAttemptAndItem(destinationAttemptId, selectedItemVersionId)
          .isPresent();
      if (!selectionMatchesProvenance) {
        return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, recomputed,
            "recomputed selected probe %s has no matching core.diagnostic_probe_provenance row for attempt %s"
                .formatted(selectedItemVersionId, destinationAttemptId));
      }
    }

    return DiagnosticSelectionV6ReplayResult.verified(destinationAttemptId, recomputed);
  }
}
