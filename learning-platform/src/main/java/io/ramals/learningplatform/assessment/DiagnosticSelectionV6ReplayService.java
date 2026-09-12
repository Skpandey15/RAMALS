package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateProbe;
import java.util.ArrayList;
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
 * AssessmentRepository#findLearnerExposedLogicalItemIds}, {@code ProbeRelationshipService.resolve},
 * or {@code DiagnosticService#resolveHypothesisProbeSelection} -- every historical input it feeds
 * back into {@link HypothesisDiscriminationDiagnosticSelector#decideFromPersistedWorkingSet} comes
 * from the immutable {@code core.diagnostic_selection_replay_input} / {@code
 * core.diagnostic_selection_replay_candidate_probe} snapshot recorded at decision time (or, for
 * {@code NO_SOURCE_ATTEMPT}, from {@link HypothesisDiscriminationDiagnosticSelector#noSourceAttemptDecision}
 * directly, never by re-searching for a source attempt), and the historical final probe selection
 * (Amendment 4 sec S) is read back verbatim from the already-exact, immutable {@code
 * core.diagnostic_probe_provenance} table, never recomputed by re-running {@code V5}'s own
 * discovery.
 *
 * <p>Every persisted audit field -- {@code activated}, {@code fallback_reason}, and the hypothesis/
 * candidate/participation counts and Step-1/Step-2 statuses -- is treated as an integrity value
 * only, never as an oracle: this class always recomputes and compares, surfacing any divergence as
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
   * Replays {@code destinationAttemptId}'s {@code DIAGNOSTIC_SELECTION_V6} decision exactly.
   *
   * <p>Follows M2-ADR-034 Amendment 4 sec O's algorithm precisely: load the destination attempt;
   * verify it was actually governed by {@code DIAGNOSTIC_SELECTION_V6} (never assumed from a
   * snapshot's mere presence -- a snapshot existing for a non-{@code V6} attempt is a contradictory
   * persisted state, reported as {@link DiagnosticSelectionV6ReplayStatus#INTEGRITY_FAILURE}, not
   * silently accepted); load the snapshot ({@link DiagnosticSelectionV6ReplayStatus#NOT_AVAILABLE}
   * if none -- pre-Amendment-4 attempts and non-{@code V6} attempts are deliberately
   * indistinguishable from outside); verify its {@code snapshot_contract_version} is one this class
   * knows how to interpret; then recompute and verify.
   *
   * @throws AttemptNotFoundException if {@code destinationAttemptId} does not exist
   */
  @Transactional(readOnly = true)
  public DiagnosticSelectionV6ReplayResult replay(UUID destinationAttemptId) {
    repository.findAttempt(destinationAttemptId)
        .orElseThrow(() -> new AttemptNotFoundException(String.valueOf(destinationAttemptId)));

    boolean governedByV6 = HypothesisDiscriminationDiagnosticSelector.SELECTION_POLICY_VERSION
        .equals(repository.findSelectionPolicy(destinationAttemptId).orElse(null));
    Optional<DiagnosticSelectionReplayInput> snapshotOpt =
        replayInputRepository.findByDestinationAttempt(destinationAttemptId);

    if (!governedByV6) {
      if (snapshotOpt.isPresent()) {
        // A replay snapshot may exist ONLY for a DIAGNOSTIC_SELECTION_V6 destination attempt
        // (mirrored as a database-level guarantee by trg_diagnostic_selection_replay_input_guard).
        // Finding one anyway is a contradictory persisted state, never silently accepted as
        // replayable.
        return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, null,
            "a DIAGNOSTIC_SELECTION_V6 replay snapshot exists for attempt %s, whose own selection_policy is not DIAGNOSTIC_SELECTION_V6"
                .formatted(destinationAttemptId));
      }
      return DiagnosticSelectionV6ReplayResult.notAvailable(destinationAttemptId);
    }
    if (snapshotOpt.isEmpty()) {
      return DiagnosticSelectionV6ReplayResult.notAvailable(destinationAttemptId);
    }
    DiagnosticSelectionReplayInput snapshot = snapshotOpt.get();

    if (!DiagnosticSelectionReplayInputRepository.SNAPSHOT_CONTRACT_VERSION
        .equals(snapshot.snapshotContractVersion())) {
      // Fail closed rather than guess compatibility from table shape -- a future _V2 contract must
      // be interpreted by its own, explicitly updated replay logic.
      return DiagnosticSelectionV6ReplayResult.unsupportedSnapshotVersion(destinationAttemptId,
          "unsupported replay snapshot contract: expected %s but found %s"
              .formatted(DiagnosticSelectionReplayInputRepository.SNAPSHOT_CONTRACT_VERSION,
                  snapshot.snapshotContractVersion()));
    }

    HypothesisDiscriminationDiagnosticSelector.Decision recomputed;
    if (snapshot.sourceAttemptId() == null) {
      // Amendment 4 sec O: reproduced directly, never by re-running source-attempt discovery.
      recomputed = HypothesisDiscriminationDiagnosticSelector.noSourceAttemptDecision();
    } else {
      List<CandidateProbe> candidateProbes = replayInputRepository.findCandidateProbes(snapshot.id());
      List<DiagnosticHypothesis> actionableHypotheses =
          replayInputRepository.findActionableHypotheses(snapshot.id());

      // Integrity hardening: the persisted header counts must match the actual child rows BEFORE
      // any Step 1/Step 2 math runs against them -- feeding a truncated or corrupted candidate set
      // into the frozen engines would be worse than refusing to replay at all.
      if (candidateProbes.size() != snapshot.candidateProbeCount()) {
        return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, null,
            "persisted candidate_probe_count=%d does not match %d actual candidate row(s) for replay input %s"
                .formatted(snapshot.candidateProbeCount(), candidateProbes.size(), snapshot.id()));
      }
      if (actionableHypotheses.size() != snapshot.actionableHypothesisCount()) {
        return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, null,
            "persisted actionable_hypothesis_count=%d does not match %d hypothes(es) reconstructed "
                + "from candidate rows for replay input %s"
                    .formatted(snapshot.actionableHypothesisCount(), actionableHypotheses.size(), snapshot.id()));
      }

      // Amendment 4 sec P: a narrow, historically-scoped lookup of the persisted candidates' own
      // immutable item metadata -- never the destination version's whole current item roster,
      // which could grow after this decision (sec P: "curriculum growth is not replay input").
      List<AdaptiveEligibleItem> itemPool = repository.findAdaptiveEligibleItemsForItemVersions(
          candidateProbes.stream().map(CandidateProbe::probeItemVersionId).distinct().toList());

      recomputed = selector.decideFromPersistedWorkingSet(
          snapshot.sourceAttemptId(), actionableHypotheses, candidateProbes,
          snapshot.relationshipAuthorizedCount(), itemPool);
    }

    return verify(destinationAttemptId, snapshot, recomputed);
  }

  private DiagnosticSelectionV6ReplayResult verify(
      UUID destinationAttemptId, DiagnosticSelectionReplayInput snapshot,
      HypothesisDiscriminationDiagnosticSelector.Decision recomputed) {
    List<String> mismatches = new ArrayList<>();
    if (recomputed.activated() != snapshot.activated()
        || !Objects.equals(recomputed.fallbackReason(), snapshot.fallbackReason())) {
      mismatches.add("activated=%s/fallbackReason=%s diverges from persisted activated=%s/fallbackReason=%s"
          .formatted(recomputed.activated(), recomputed.fallbackReason(), snapshot.activated(),
              snapshot.fallbackReason()));
    }
    if (recomputed.candidateProbeCount() != snapshot.candidateProbeCount()) {
      mismatches.add("recomputed candidateProbeCount=%d diverges from persisted candidate_probe_count=%d"
          .formatted(recomputed.candidateProbeCount(), snapshot.candidateProbeCount()));
    }
    if (recomputed.actionableHypothesisCount() != snapshot.actionableHypothesisCount()) {
      mismatches.add("recomputed actionableHypothesisCount=%d diverges from persisted actionable_hypothesis_count=%d"
          .formatted(recomputed.actionableHypothesisCount(), snapshot.actionableHypothesisCount()));
    }
    if (recomputed.participatingHypothesisCount() != snapshot.participatingHypothesisCount()) {
      mismatches.add("recomputed participatingHypothesisCount=%d diverges from persisted participating_hypothesis_count=%d"
          .formatted(recomputed.participatingHypothesisCount(), snapshot.participatingHypothesisCount()));
    }
    if (!Objects.equals(recomputed.step1Status(), snapshot.step1Status())) {
      mismatches.add("recomputed step1Status=%s diverges from persisted step1_status=%s"
          .formatted(recomputed.step1Status(), snapshot.step1Status()));
    }
    if (!Objects.equals(recomputed.step2Status(), snapshot.step2Status())) {
      mismatches.add("recomputed step2Status=%s diverges from persisted step2_status=%s"
          .formatted(recomputed.step2Status(), snapshot.step2Status()));
    }
    // relationshipAuthorizedHypothesisCount is threaded straight through from the persisted
    // snapshot into decideFromPersistedWorkingSet's own WorkingSet -- Amendment 4 does not require,
    // and this class does not attempt, independently recomputing it from current curriculum -- so
    // this always holds by construction today. Kept as an explicit, checked invariant (rather than
    // silently assumed) so a future refactor that accidentally stopped passing it through verbatim
    // would be caught here, not discovered later as a silent replay defect.
    if (recomputed.relationshipAuthorizedHypothesisCount() != snapshot.relationshipAuthorizedCount()) {
      mismatches.add("recomputed relationshipAuthorizedHypothesisCount=%d diverges from persisted relationship_authorized_count=%d"
          .formatted(recomputed.relationshipAuthorizedHypothesisCount(), snapshot.relationshipAuthorizedCount()));
    }
    if (!mismatches.isEmpty()) {
      return DiagnosticSelectionV6ReplayResult.integrityFailure(
          destinationAttemptId, recomputed, String.join("; ", mismatches));
    }

    // Amendment 4 sec S: selected-probe verification is a SEPARATE concern from decision replay,
    // already exact and immutable today via core.diagnostic_probe_provenance -- whether V6 itself
    // activated and chose the probe, or V6 fell back and V5's own resolveHypothesisProbeSelection
    // chose it. Never re-run either selector's own discovery here.
    List<ProbeProvenance> provenanceRows = probeProvenanceRepository.findByAttempt(destinationAttemptId);
    if (provenanceRows.size() > 1) {
      return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, recomputed,
          "%d core.diagnostic_probe_provenance rows exist for attempt %s -- at most one is possible "
              + "(MAX_HYPOTHESIS_PROBES_PER_PACKET = 1)"
                  .formatted(provenanceRows.size(), destinationAttemptId));
    }
    ProbeProvenance provenance = provenanceRows.isEmpty() ? null : provenanceRows.get(0);

    if (recomputed.activated()) {
      UUID winner = recomputed.selection()
          .orElseThrow(() -> new IllegalStateException("activated Decision must carry a selection"))
          .chosenItemVersionId();
      if (provenance == null || !provenance.itemVersionId().equals(winner)) {
        return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, recomputed,
            "recomputed V6-activated probe %s has no matching core.diagnostic_probe_provenance row for attempt %s"
                .formatted(winner, destinationAttemptId));
      }
    } else {
      // A fallback decision with no source attempt, or with zero actionable hypotheses, could not
      // possibly have had V5 produce a hypothesis-driven probe either: resolveHypothesisProbeSelection
      // requires the identical source attempt, and the identical destination-eligibility gate V6's
      // own enumeration already found nothing survived. Provenance existing anyway is corruption,
      // never verified by re-deriving what V5 "should" have chosen.
      boolean noProbeCouldPossiblyExist =
          recomputed.sourceAttemptId() == null || recomputed.actionableHypothesisCount() == 0;
      if (noProbeCouldPossiblyExist && provenance != null) {
        return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, recomputed,
            "fallback decision (sourceAttemptId=%s, actionableHypothesisCount=%d) could not have "
                + "produced a hypothesis-driven probe, but core.diagnostic_probe_provenance records "
                + "one (%s) for attempt %s"
                    .formatted(recomputed.sourceAttemptId(), recomputed.actionableHypothesisCount(),
                        provenance.itemVersionId(), destinationAttemptId));
      }
    }

    return DiagnosticSelectionV6ReplayResult.verified(destinationAttemptId, recomputed, provenance);
  }
}
