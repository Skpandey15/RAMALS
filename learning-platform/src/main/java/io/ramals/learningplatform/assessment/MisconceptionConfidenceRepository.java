package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.observability.UuidV7;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * M2-ADR-028: reads accumulated {@code MISCONCEPTION_EVIDENCE_V1} evidence for one {@code (learner,
 * misconception)} pair, and is the only writer of {@code core.misconception_confidence_observation}/
 * {@code core.misconception_confidence_observation_evidence}. Reads directly from {@code
 * core.misconception_evidence_observation} rather than through {@link
 * MisconceptionEvidenceObservationRepository} -- the same choice H5's own {@code
 * DiagnosticConfidenceRepository.evidenceCounts} already made against {@code
 * core.diagnostic_probe_provenance}, never delegating to that table's own writer repository.
 */
@Repository
public class MisconceptionConfidenceRepository {

  private final JdbcTemplate jdbcTemplate;

  public MisconceptionConfidenceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Every distinct misconception this attempt's own responses produced {@code
   * MISCONCEPTION_EVIDENCE_V1} evidence for -- what {@link MisconceptionConfidenceService} recomputes
   * exactly once each, after the whole per-response loop finishes (M2-ADR-028 §4). Runs inside the
   * same transaction that just captured this attempt's evidence, so those not-yet-committed rows are
   * already visible to this query -- the same trick H5's own {@code evidenceCounts} query relies on.
   */
  public Set<UUID> distinctMisconceptionIdsForAttempt(UUID attemptId) {
    List<UUID> ids = jdbcTemplate.query("""
        SELECT DISTINCT o.misconception_id
        FROM core.misconception_evidence_observation o
        JOIN core.assessment_response r ON r.id = o.response_id
        WHERE r.attempt_id = ? AND o.policy_version = ?
        """, (result, row) -> result.getObject("misconception_id", UUID.class),
        attemptId, MisconceptionEvidenceCaptureService.POLICY);
    return new LinkedHashSet<>(ids);
  }

  /**
   * The complete {@code MISCONCEPTION_EVIDENCE_V1} evidence set for one {@code (learner,
   * misconception)} pair, at the moment of this read -- every {@code SUPPORTING}, {@code
   * CONTRADICTORY}, and {@code INCONCLUSIVE} observation, across every attempt that ever produced one
   * (M2-ADR-028 §2: cross-assessment-version aggregation for the same {@code misconception_id} is
   * deliberate). {@link MisconceptionConfidenceService} derives this snapshot's counts, calculator
   * input, band, and provenance ids all from this SAME read -- never from two separate queries that
   * could disagree.
   */
  public List<EvidenceObservationSummary> evidenceObservationsFor(UUID learnerId, UUID misconceptionId) {
    return jdbcTemplate.query("""
        SELECT id, outcome
        FROM core.misconception_evidence_observation
        WHERE learner_id = ? AND misconception_id = ? AND policy_version = ?
        """, (result, row) -> new EvidenceObservationSummary(
            result.getObject("id", UUID.class),
            MisconceptionEvidenceOutcome.valueOf(result.getString("outcome"))),
        learnerId, misconceptionId, MisconceptionEvidenceCaptureService.POLICY);
  }

  /** One evidence observation's id and outcome, as read by {@link #evidenceObservationsFor} -- the
   * only shape {@link MisconceptionConfidenceService} needs from each row. */
  public record EvidenceObservationSummary(UUID id, MisconceptionEvidenceOutcome outcome) {
  }

  /** Appends one immutable confidence snapshot. Written inside the same transaction that captured
   * this submission's own evidence -- {@code trg_misconception_confidence_observation_guard} forbids
   * ever updating or deleting it afterward. */
  public UUID insert(
      UUID attemptId, UUID learnerId, UUID misconceptionId, DiagnosticConfidenceResult result) {
    UUID id = UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.misconception_confidence_observation
          (id, attempt_id, learner_id, misconception_id, supporting_count, contradictory_count,
           inconclusive_count, band, policy_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, attemptId, learnerId, misconceptionId, result.supportingCount(),
        result.contradictoryCount(), result.inconclusiveCount(), result.band().name(),
        result.policyVersion());
    return id;
  }

  /** Inserts one provenance row -- one of the (possibly several) evidence observations this
   * confidence snapshot's complete evidentiary boundary includes (M2-ADR-028 §7). */
  public void insertProvenance(UUID confidenceObservationId, UUID evidenceObservationId) {
    jdbcTemplate.update("""
        INSERT INTO core.misconception_confidence_observation_evidence
          (confidence_observation_id, evidence_observation_id)
        VALUES (?, ?)
        """, confidenceObservationId, evidenceObservationId);
  }

  /** Reads back one snapshot, for audit -- at most one, by this table's own {@code UNIQUE(attempt_id,
   * misconception_id)}. */
  public Optional<MisconceptionConfidenceObservation> findByAttemptAndMisconception(
      UUID attemptId, UUID misconceptionId) {
    return jdbcTemplate.query("""
        SELECT id, attempt_id, learner_id, misconception_id, supporting_count, contradictory_count,
               inconclusive_count, band, policy_version, created_at
        FROM core.misconception_confidence_observation
        WHERE attempt_id = ? AND misconception_id = ?
        """, OBSERVATION_MAPPER, attemptId, misconceptionId).stream().findFirst();
  }

  public Optional<MisconceptionConfidenceObservation> findById(UUID id) {
    return jdbcTemplate.query("""
        SELECT id, attempt_id, learner_id, misconception_id, supporting_count, contradictory_count,
               inconclusive_count, band, policy_version, created_at
        FROM core.misconception_confidence_observation
        WHERE id = ?
        """, OBSERVATION_MAPPER, id).stream().findFirst();
  }

  /** The single most recently computed snapshot for one {@code (learner, misconception)} pair -- the
   * current read-side answer, without a separate projection table (M2-ADR-028's rejected hybrid
   * alternative). */
  public Optional<MisconceptionConfidenceObservation> findLatestFor(UUID learnerId, UUID misconceptionId) {
    return jdbcTemplate.query("""
        SELECT id, attempt_id, learner_id, misconception_id, supporting_count, contradictory_count,
               inconclusive_count, band, policy_version, created_at
        FROM core.misconception_confidence_observation
        WHERE learner_id = ? AND misconception_id = ?
        ORDER BY created_at DESC
        LIMIT 1
        """, OBSERVATION_MAPPER, learnerId, misconceptionId).stream().findFirst();
  }

  /** Every evidence observation id cited as this snapshot's own complete provenance -- the exact
   * evidentiary boundary M2-ADR-028 §7 requires, for audit and for reconstruction tests. */
  public List<UUID> findProvenanceFor(UUID confidenceObservationId) {
    return jdbcTemplate.query("""
        SELECT evidence_observation_id
        FROM core.misconception_confidence_observation_evidence
        WHERE confidence_observation_id = ?
        """, (result, row) -> result.getObject("evidence_observation_id", UUID.class),
        confidenceObservationId);
  }

  private static final RowMapper<MisconceptionConfidenceObservation> OBSERVATION_MAPPER =
      (result, row) -> new MisconceptionConfidenceObservation(
          result.getObject("id", UUID.class),
          result.getObject("attempt_id", UUID.class),
          result.getObject("learner_id", UUID.class),
          result.getObject("misconception_id", UUID.class),
          result.getInt("supporting_count"),
          result.getInt("contradictory_count"),
          result.getInt("inconclusive_count"),
          DiagnosticConfidenceBand.valueOf(result.getString("band")),
          result.getString("policy_version"),
          instant(result.getObject("created_at", OffsetDateTime.class)));

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
