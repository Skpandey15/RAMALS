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
    List<CandidateProbe> persistedCandidateProbes;
    if (snapshot.sourceAttemptId() == null) {
      // Amendment 4 sec O: reproduced directly, never by re-running source-attempt discovery.
      recomputed = HypothesisDiscriminationDiagnosticSelector.noSourceAttemptDecision();
      persistedCandidateProbes = List.of();
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
      persistedCandidateProbes = candidateProbes;
    }

    return verify(destinationAttemptId, snapshot, recomputed, persistedCandidateProbes);
  }

  private DiagnosticSelectionV6ReplayResult verify(
      UUID destinationAttemptId, DiagnosticSelectionReplayInput snapshot,
      HypothesisDiscriminationDiagnosticSelector.Decision recomputed,
      List<CandidateProbe> persistedCandidateProbes) {
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

    String finalProbeMismatch = verifyFinalProbeProvenance(
        destinationAttemptId, snapshot, recomputed, persistedCandidateProbes, provenance);
    if (finalProbeMismatch != null) {
      return DiagnosticSelectionV6ReplayResult.integrityFailure(destinationAttemptId, recomputed, finalProbeMismatch);
    }

    return DiagnosticSelectionV6ReplayResult.verified(destinationAttemptId, recomputed, provenance);
  }

  /**
   * Amendment 4 sec S: verifies the historical final probe -- whether {@code V6} itself activated
   * and chose it, or {@code V6} fell back and {@code V5}'s own already-governed {@code
   * resolveHypothesisProbeSelection} chose it -- against the already-exact, immutable {@code
   * core.diagnostic_probe_provenance} record, without ever re-running either selector's own
   * discovery. Returns a human-readable mismatch description, or {@code null} when the persisted
   * (or legitimately absent) provenance is consistent with this replay.
   *
   * <p><b>V6-activated ({@code recomputed.activated()}):</b> the recomputed winner's full identity
   * (item, trigger item/objective, relationship type, target objective, authorizing relationship,
   * and source attempt) must match provenance exactly -- an activated winner is the direct output of
   * {@code V6}'s own ranking over its own persisted candidate set, so a match is always required.
   *
   * <p><b>{@code V6} fallback:</b> provenance is never required to exist. {@code
   * HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe}'s own packet-composition rule
   * -- and, before that, {@code resolveHypothesisProbeSelection}'s own destination-eligibility gate
   * -- can legitimately exclude a candidate {@code V5} itself chose from ever reaching {@code
   * core.diagnostic_probe_provenance} (a band-cap exclusion, decided by mastery/evidence state this
   * snapshot deliberately never persists -- Amendment 4 sec Z), so a fallback attempt with no
   * provenance row is never itself a failure. When provenance IS present, its {@code
   * source_attempt_id} must equal the persisted {@code sourceAttemptId} (both {@code V6} and {@code
   * V5} resolve the same "immediately preceding completed attempt" fact within the same
   * transaction), and -- unless {@code V6}'s own working-set walk stopped early at {@code
   * MAX_AUTHORIZED_HYPOTHESES_V6} -- its full hypothesis/item identity must match one of the
   * persisted candidate probes: {@code V5}'s own resolution walks the identical (miss, relationship
   * type) enumeration {@code V6}'s own working-set walk does, so whenever that walk runs to
   * completion (never capped early), any candidate {@code V5} could possibly choose was already
   * evaluated -- and, if destination-eligible, already admitted -- by {@code V6} too. Only when
   * {@code V6}'s walk stopped early can {@code V5}'s own uncapped walk legitimately reach a pair
   * {@code V6}'s own snapshot never recorded; that divergence is not itself re-derivable without
   * re-running {@code V5}'s discovery, so it is not treated as corruption.
   */
  private String verifyFinalProbeProvenance(
      UUID destinationAttemptId, DiagnosticSelectionReplayInput snapshot,
      HypothesisDiscriminationDiagnosticSelector.Decision recomputed,
      List<CandidateProbe> persistedCandidateProbes, ProbeProvenance provenance) {
    if (recomputed.activated()) {
      HypothesisDrivenProbeDiagnosticSelector.Selection winner = recomputed.selection()
          .orElseThrow(() -> new IllegalStateException("activated Decision must carry a selection"));
      boolean matches = provenance != null
          && provenance.attemptId().equals(destinationAttemptId)
          && Objects.equals(provenance.sourceAttemptId(), winner.sourceAttemptId())
          && identityMatches(provenance, winner.hypothesis(), winner.chosenItemVersionId());
      if (!matches) {
        return "recomputed V6-activated selection (item=%s) has no matching "
            + "core.diagnostic_probe_provenance row for attempt %s"
                .formatted(winner.chosenItemVersionId(), destinationAttemptId);
      }
      return null;
    }

    // Fallback: provenance absence is never itself a failure (see javadoc) -- V5's own
    // packet-composition/band-cap rules can legitimately exclude any candidate from ever reaching
    // core.diagnostic_probe_provenance, and neither fact is reconstructable from this snapshot.
    if (provenance == null) {
      return null;
    }

    boolean noProbeCouldPossiblyExist =
        recomputed.sourceAttemptId() == null || recomputed.actionableHypothesisCount() == 0;
    if (noProbeCouldPossiblyExist) {
      return "fallback decision (sourceAttemptId=%s, actionableHypothesisCount=%d) could not have "
          + "produced a hypothesis-driven probe, but core.diagnostic_probe_provenance records one "
          + "(%s) for attempt %s"
              .formatted(recomputed.sourceAttemptId(), recomputed.actionableHypothesisCount(),
                  provenance.itemVersionId(), destinationAttemptId);
    }

    if (!provenance.attemptId().equals(destinationAttemptId)) {
      return "core.diagnostic_probe_provenance row's own attempt_id %s does not match destination attempt %s"
          .formatted(provenance.attemptId(), destinationAttemptId);
    }
    if (!Objects.equals(provenance.sourceAttemptId(), snapshot.sourceAttemptId())) {
      return "core.diagnostic_probe_provenance source_attempt_id %s diverges from the persisted "
          + "snapshot's sourceAttemptId %s"
              .formatted(provenance.sourceAttemptId(), snapshot.sourceAttemptId());
    }

    boolean v6WalkWasGuaranteedComplete = snapshot.actionableHypothesisCount()
        < HypothesisDiscriminationDiagnosticSelector.MAX_AUTHORIZED_HYPOTHESES_V6;
    if (v6WalkWasGuaranteedComplete) {
      boolean matchesAPersistedCandidate = persistedCandidateProbes.stream()
          .anyMatch(candidate -> identityMatches(provenance, candidate.hypothesis(), candidate.probeItemVersionId()));
      if (!matchesAPersistedCandidate) {
        return "core.diagnostic_probe_provenance row (item=%s) does not match any persisted candidate "
            + "probe for replay input %s, and V6's own working-set walk completed without hitting "
            + "MAX_AUTHORIZED_HYPOTHESES_V6 -- V5's own pick must have been among V6's own candidates"
                .formatted(provenance.itemVersionId(), snapshot.id());
      }
    }
    // Else: V6's walk stopped early at the cap -- V5's own, uncapped walk may legitimately have
    // reached a candidate V6's own snapshot never recorded (see javadoc); not corruption.
    return null;
  }

  private static boolean identityMatches(
      ProbeProvenance provenance, DiagnosticHypothesis hypothesis, UUID probeItemVersionId) {
    return provenance.itemVersionId().equals(probeItemVersionId)
        && provenance.sourceItemVersionId().equals(hypothesis.triggerItemVersionId())
        && provenance.sourceObjectiveId().equals(hypothesis.triggerObjectiveId())
        && provenance.relationshipType() == hypothesis.relationshipType()
        && provenance.targetObjectiveId().equals(hypothesis.targetObjectiveId())
        && Objects.equals(provenance.authorizingRelationshipId(), hypothesis.authorizingRelationshipId());
  }
}
