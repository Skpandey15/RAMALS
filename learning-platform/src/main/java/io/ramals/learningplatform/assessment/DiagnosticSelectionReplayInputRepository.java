package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateProbe;
import io.ramals.learningplatform.observability.UuidV7;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M2-ADR-034 Amendment 4 (Step 4): the only writer of {@code
 * core.diagnostic_selection_replay_input} / {@code core.diagnostic_selection_replay_candidate_probe}.
 * Deliberately its own class, mirroring {@link ProbeProvenanceRepository}'s own precedent -- this is
 * the runtime capability that persists a {@code DIAGNOSTIC_SELECTION_V6} decision's replay inputs,
 * not a concern {@link HypothesisDiscriminationDiagnosticSelector} (which decides, but persists
 * nothing itself) or {@link AssessmentRepository} owns.
 *
 * <p><b>Persist WHICH source attempt was used; reconstruct WHAT that source attempt contained.</b>
 * Every row this class writes is exactly the working set {@link HypothesisDiscriminationDiagnosticSelector#select}
 * actually evaluated -- never re-derived, never a superset "for safety."
 */
@Repository
public class DiagnosticSelectionReplayInputRepository {

  /**
   * The frozen snapshot-contract identifier -- distinct from {@code DIAGNOSTIC_SELECTION_V6} (the
   * live selection policy, unchanged by this amendment) and from {@code HYPOTHESIS_UNCERTAINTY_V1}
   * / {@code HYPOTHESIS_DISCRIMINATION_V1} (the frozen engines, also unchanged). Versions the
   * *snapshot schema*, not any selection or scoring mathematics: if this schema ever needs a
   * breaking change, mint {@code DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V2} and leave every snapshot
   * already recorded under {@code _V1} untouched, exactly the discipline every other frozen
   * identifier in this codebase already holds to.
   */
  public static final String SNAPSHOT_CONTRACT_VERSION = "DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1";

  private final JdbcTemplate jdbcTemplate;

  public DiagnosticSelectionReplayInputRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Persists exactly one snapshot for {@code destinationAttemptId}: one header row (regardless of
   * outcome -- Amendment 4 §J) plus one candidate-probe row per surviving candidate in {@code
   * decision}'s own working set, in the same transaction the caller ({@code
   * DiagnosticService#createAttempt}) already runs. Never called twice for the same attempt --
   * {@code createAttempt}'s own idempotency short-circuit guarantees {@code V6} evaluates at most
   * once per attempt actually inserted; {@code destination_attempt_id}'s {@code UNIQUE} constraint
   * is defense-in-depth on top of that, not the primary mechanism.
   */
  public void insert(UUID destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.Decision decision) {
    UUID replayInputId = UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.diagnostic_selection_replay_input
          (id, destination_attempt_id, source_attempt_id, snapshot_contract_version,
           relationship_authorized_count, actionable_hypothesis_count, candidate_probe_count,
           participating_hypothesis_count, step1_status, step2_status, activated, fallback_reason)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, replayInputId, destinationAttemptId, decision.sourceAttemptId(), SNAPSHOT_CONTRACT_VERSION,
        decision.relationshipAuthorizedHypothesisCount(), decision.actionableHypothesisCount(),
        decision.candidateProbeCount(), decision.participatingHypothesisCount(),
        decision.step1Status(), decision.step2Status(), decision.activated(),
        decision.fallbackReason() == null ? null : decision.fallbackReason().name());

    List<CandidateProbe> candidateProbes = decision.candidateProbes();
    if (candidateProbes.isEmpty()) {
      return;
    }
    // Amendment 4 §I: admission_ordinal is audit-only, never authoritative -- it records the order
    // candidates were actually admitted in (misses in presentation_order x
    // RELATIONSHIP_TYPE_PRIORITY), never a ranking replay may rely on.
    List<Object[]> rows = new java.util.ArrayList<>(candidateProbes.size());
    int ordinal = 0;
    for (CandidateProbe candidate : candidateProbes) {
      ordinal++;
      DiagnosticHypothesis hypothesis = candidate.hypothesis();
      rows.add(new Object[] {
          UuidV7.generate(), replayInputId, ordinal, candidate.probeItemVersionId(), candidate.scoreable(),
          hypothesis.triggerItemVersionId(), hypothesis.triggerObjectiveId(),
          hypothesis.relationshipType().name(), hypothesis.targetObjectiveId(),
          hypothesis.authorizingRelationshipId()
      });
    }
    jdbcTemplate.batchUpdate("""
        INSERT INTO core.diagnostic_selection_replay_candidate_probe
          (id, replay_input_id, admission_ordinal, probe_item_version_id, scoreable,
           trigger_item_version_id, trigger_objective_id, relationship_type, target_objective_id,
           authorizing_relationship_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, rows);
  }

  /** Reads back the header row for one destination attempt, for replay or audit. Empty iff this
   * attempt predates Amendment 4's implementation -- exact replay is not available for it, and this
   * method's own emptiness is the sole, honest signal of that (never a {@code created_at} guess). */
  public Optional<DiagnosticSelectionReplayInput> findByDestinationAttempt(UUID destinationAttemptId) {
    return jdbcTemplate.query("""
        SELECT id, destination_attempt_id, source_attempt_id, snapshot_contract_version,
               relationship_authorized_count, actionable_hypothesis_count, candidate_probe_count,
               participating_hypothesis_count, step1_status, step2_status, activated, fallback_reason
        FROM core.diagnostic_selection_replay_input
        WHERE destination_attempt_id = ?
        """, (result, row) -> new DiagnosticSelectionReplayInput(
            result.getObject("id", UUID.class),
            result.getObject("destination_attempt_id", UUID.class),
            result.getObject("source_attempt_id", UUID.class),
            result.getString("snapshot_contract_version"),
            result.getInt("relationship_authorized_count"),
            result.getInt("actionable_hypothesis_count"),
            result.getInt("candidate_probe_count"),
            result.getInt("participating_hypothesis_count"),
            result.getString("step1_status"),
            result.getString("step2_status"),
            result.getBoolean("activated"),
            result.getString("fallback_reason") == null
                ? null : V6FallbackReason.valueOf(result.getString("fallback_reason"))),
        destinationAttemptId).stream().findFirst();
  }

  /**
   * Reads back the exact surviving candidate probes for one replay-input snapshot, in {@code
   * admission_ordinal} order for deterministic (audit-legible) iteration -- never an order replay's
   * own ranking may depend on (Amendment 4 §I). Each row's full five-field {@link DiagnosticHypothesis}
   * identity is reconstructed verbatim, never collapsed to a partial key.
   */
  public List<CandidateProbe> findCandidateProbes(UUID replayInputId) {
    return jdbcTemplate.query("""
        SELECT probe_item_version_id, scoreable, trigger_item_version_id, trigger_objective_id,
               relationship_type, target_objective_id, authorizing_relationship_id
        FROM core.diagnostic_selection_replay_candidate_probe
        WHERE replay_input_id = ?
        ORDER BY admission_ordinal
        """, (result, row) -> new CandidateProbe(
            result.getObject("probe_item_version_id", UUID.class),
            new DiagnosticHypothesis(
                result.getObject("trigger_item_version_id", UUID.class),
                result.getObject("trigger_objective_id", UUID.class),
                ProbeRelationshipType.valueOf(result.getString("relationship_type")),
                result.getObject("target_objective_id", UUID.class),
                result.getObject("authorizing_relationship_id", UUID.class)),
            result.getBoolean("scoreable")),
        replayInputId);
  }

  /**
   * The distinct actionable hypotheses admitted into one replay-input snapshot's working set, in
   * {@code admission_ordinal} order of first appearance. Derived from {@link
   * #findCandidateProbes}'s own rows rather than stored a second time: every {@code V6}-actionable
   * hypothesis has, by its own definition (Amendment 3 §H), at least one surviving candidate, so no
   * information is lost by reconstructing the hypothesis set from its candidates.
   */
  public List<DiagnosticHypothesis> findActionableHypotheses(UUID replayInputId) {
    List<DiagnosticHypothesis> hypotheses = new java.util.ArrayList<>();
    for (CandidateProbe candidate : findCandidateProbes(replayInputId)) {
      if (!hypotheses.contains(candidate.hypothesis())) {
        hypotheses.add(candidate.hypothesis());
      }
    }
    return List.copyOf(hypotheses);
  }
}
