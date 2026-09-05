package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentItemType;
import io.ramals.learningplatform.observability.UuidV7;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-023 §2): reads every distinct evidence observation gathered so far
 * for one hypothesis tuple, and is the only writer of {@code core.diagnostic_confidence_observation}.
 * Never touches {@code core.diagnostic_probe_provenance} or {@code core.diagnostic_probe_relationship}
 * except to read -- H5 consumes H4b/V5's models, it does not alter them (M2-ADR-024 §1,
 * M2-ADR-025 §8).
 */
@Repository
public class DiagnosticConfidenceRepository {

  private final JdbcTemplate jdbcTemplate;

  public DiagnosticConfidenceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Every distinct evidence observation for this hypothesis tuple: every {@code
   * core.diagnostic_probe_provenance} row this learner has, matching {@code sourceObjectiveId}/
   * {@code targetObjectiveId}/{@code relationshipType}, scoped to {@code assessmentVersionId} --
   * the same scoping boundary V5's own source-attempt lookup already uses (M2-ADR-025 §4), and
   * naturally where evidence would stop lining up anyway ({@code learning_objective.id} is itself
   * curriculum-version-scoped). Each row's own already-written {@code core.assessment_response} is
   * classified by the exact, unmodified {@link HypothesisEvidenceOutcome#classify} every earlier
   * H4b evidence read already uses -- never re-derived or re-interpreted here.
   *
   * <p>Runs inside the same transaction that just inserted the triggering response, so that
   * response is already visible to this query (same connection, same not-yet-committed
   * transaction) -- it is one of the rows counted, not a special case added afterward.
   */
  public RawEvidenceCounts evidenceCounts(
      UUID learnerId, UUID assessmentVersionId, UUID sourceObjectiveId, UUID targetObjectiveId,
      ProbeRelationshipType relationshipType) {
    List<ScoredProbe> scoredProbes = jdbcTemplate.query("""
        SELECT ar.is_correct, iv.item_type
        FROM core.diagnostic_probe_provenance p
        JOIN core.assessment_attempt a ON a.id = p.attempt_id
        JOIN core.assessment_response ar
          ON ar.attempt_id = p.attempt_id AND ar.item_version_id = p.item_version_id
        JOIN core.assessment_item_version iv ON iv.id = p.item_version_id
        WHERE a.learner_id = ? AND a.assessment_version_id = ?
          AND p.source_objective_id = ? AND p.target_objective_id = ? AND p.relationship_type = ?
        """, (result, row) -> new ScoredProbe(
            result.getBoolean("is_correct"), result.getString("item_type")),
        learnerId, assessmentVersionId, sourceObjectiveId, targetObjectiveId, relationshipType.name());

    int supporting = 0;
    int contradictory = 0;
    int inconclusive = 0;
    for (ScoredProbe scored : scoredProbes) {
      HypothesisEvidenceOutcome outcome = HypothesisEvidenceOutcome.classify(
          AssessmentItemType.of(scored.itemType()), scored.isCorrect());
      switch (outcome) {
        case SUPPORTING -> supporting++;
        case CONTRADICTORY -> contradictory++;
        case INCONCLUSIVE -> inconclusive++;
      }
    }
    return new RawEvidenceCounts(supporting, contradictory, inconclusive);
  }

  /** Appends one immutable observation. Written inside the same transaction that scored the
   * triggering probe response, while that response's own attempt is still visible as the current
   * submission -- {@code trg_diagnostic_confidence_observation_guard} forbids ever updating or
   * deleting it afterward. */
  public DiagnosticConfidenceObservation insert(
      UUID learnerId, UUID sourceObjectiveId, UUID targetObjectiveId,
      ProbeRelationshipType relationshipType, UUID triggeringProvenanceId,
      DiagnosticConfidenceResult result) {
    UUID id = UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.diagnostic_confidence_observation
          (id, learner_id, source_objective_id, target_objective_id, relationship_type,
           triggering_provenance_id, supporting_count, contradictory_count, inconclusive_count,
           band, policy_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, learnerId, sourceObjectiveId, targetObjectiveId, relationshipType.name(),
        triggeringProvenanceId, result.supportingCount(), result.contradictoryCount(),
        result.inconclusiveCount(), result.band().name(), result.policyVersion());
    return findById(id).orElseThrow(() -> new IllegalStateException(
        "diagnostic_confidence_observation row disappeared immediately after insert: " + id));
  }

  /** Reads back one observation, for audit. */
  public Optional<DiagnosticConfidenceObservation> findById(UUID id) {
    return jdbcTemplate.query("""
        SELECT id, learner_id, source_objective_id, target_objective_id, relationship_type,
               triggering_provenance_id, supporting_count, contradictory_count, inconclusive_count,
               band, policy_version, created_at
        FROM core.diagnostic_confidence_observation
        WHERE id = ?
        """, OBSERVATION_MAPPER, id).stream().findFirst();
  }

  /** Reads back the observation a specific probe response triggered, for audit -- at most one, by
   * this table's own {@code UNIQUE(triggering_provenance_id)}. */
  public Optional<DiagnosticConfidenceObservation> findByTriggeringProvenanceId(
      UUID triggeringProvenanceId) {
    return jdbcTemplate.query("""
        SELECT id, learner_id, source_objective_id, target_objective_id, relationship_type,
               triggering_provenance_id, supporting_count, contradictory_count, inconclusive_count,
               band, policy_version, created_at
        FROM core.diagnostic_confidence_observation
        WHERE triggering_provenance_id = ?
        """, OBSERVATION_MAPPER, triggeringProvenanceId).stream().findFirst();
  }

  private static final org.springframework.jdbc.core.RowMapper<DiagnosticConfidenceObservation>
      OBSERVATION_MAPPER = (result, row) -> new DiagnosticConfidenceObservation(
          result.getObject("id", UUID.class),
          result.getObject("learner_id", UUID.class),
          result.getObject("source_objective_id", UUID.class),
          result.getObject("target_objective_id", UUID.class),
          ProbeRelationshipType.valueOf(result.getString("relationship_type")),
          result.getObject("triggering_provenance_id", UUID.class),
          result.getInt("supporting_count"),
          result.getInt("contradictory_count"),
          result.getInt("inconclusive_count"),
          DiagnosticConfidenceBand.valueOf(result.getString("band")),
          result.getString("policy_version"),
          instant(result.getObject("created_at", OffsetDateTime.class)));

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  /** The raw scoring fact behind one counted probe -- see {@link #evidenceCounts}. */
  private record ScoredProbe(boolean isCorrect, String itemType) {
  }

  /** Every distinct evidence observation counted for one hypothesis tuple, before
   * {@link DiagnosticConfidenceCalculatorV1} turns it into a band. */
  public record RawEvidenceCounts(int supportingCount, int contradictoryCount, int inconclusiveCount) {
  }
}
