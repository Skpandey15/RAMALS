package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.observability.UuidV7;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Granular diagnostic runtime evidence capture (M2-ADR-027): the only writer of
 * {@code core.misconception_evidence_observation}/{@code core.misconception_evidence_observation_
 * mapping}. Both tables are append-only, and both guard triggers independently re-verify what this
 * class writes -- an observation's own {@code outcome} is re-derived by
 * {@code trg_misconception_evidence_observation_guard} from authoritative facts alone (never from
 * the provenance rows this class writes alongside it), and each provenance row's own event-time
 * proof is re-verified by {@code trg_misconception_evidence_observation_mapping_guard}. This class
 * is trusted to compute the right values, but never trusted alone -- the database proves it too.
 */
@Repository
public class MisconceptionEvidenceObservationRepository {

  private final JdbcTemplate jdbcTemplate;

  public MisconceptionEvidenceObservationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Inserts one immutable observation. */
  public UUID insert(
      UUID learnerId, UUID responseId, UUID misconceptionId, MisconceptionEvidenceOutcome outcome,
      String policyVersion) {
    UUID id = UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.misconception_evidence_observation
          (id, response_id, learner_id, misconception_id, outcome, policy_version)
        VALUES (?, ?, ?, ?, ?, ?)
        """, id, responseId, learnerId, misconceptionId, outcome.name(), policyVersion);
    return id;
  }

  /** Inserts one provenance row -- one of the (possibly several) mapping rows that made
   * {@code observationId}'s hypothesis tuple event-time-eligible. */
  public void insertProvenance(
      UUID observationId, UUID itemVersionId, String optionId, UUID misconceptionId) {
    jdbcTemplate.update("""
        INSERT INTO core.misconception_evidence_observation_mapping
          (observation_id, item_version_id, option_id, misconception_id)
        VALUES (?, ?, ?, ?)
        """, observationId, itemVersionId, optionId, misconceptionId);
  }

  /** Reads back one observation, for audit -- at most one, by the table's own
   * {@code UNIQUE(response_id, misconception_id)}. */
  public Optional<MisconceptionEvidenceObservation> findByResponseAndMisconception(
      UUID responseId, UUID misconceptionId) {
    return jdbcTemplate.query("""
        SELECT id, response_id, learner_id, misconception_id, outcome, policy_version, created_at
        FROM core.misconception_evidence_observation
        WHERE response_id = ? AND misconception_id = ?
        """, OBSERVATION_MAPPER, responseId, misconceptionId).stream().findFirst();
  }

  public Optional<MisconceptionEvidenceObservation> findById(UUID id) {
    return jdbcTemplate.query("""
        SELECT id, response_id, learner_id, misconception_id, outcome, policy_version, created_at
        FROM core.misconception_evidence_observation
        WHERE id = ?
        """, OBSERVATION_MAPPER, id).stream().findFirst();
  }

  /** Every provenance row attached to one observation -- the complete event-time-eligible mapping
   * set it cites (M2-ADR-027 §6), for audit. */
  public List<ProvenanceRow> findProvenanceFor(UUID observationId) {
    return jdbcTemplate.query("""
        SELECT item_version_id, option_id, misconception_id
        FROM core.misconception_evidence_observation_mapping
        WHERE observation_id = ?
        """, (result, row) -> new ProvenanceRow(
            result.getObject("item_version_id", UUID.class),
            result.getString("option_id"),
            result.getObject("misconception_id", UUID.class)),
        observationId);
  }

  /** One cited mapping row -- see {@link #findProvenanceFor}. */
  public record ProvenanceRow(UUID itemVersionId, String optionId, UUID misconceptionId) {
  }

  private static final RowMapper<MisconceptionEvidenceObservation> OBSERVATION_MAPPER =
      (result, row) -> new MisconceptionEvidenceObservation(
          result.getObject("id", UUID.class),
          result.getObject("response_id", UUID.class),
          result.getObject("learner_id", UUID.class),
          result.getObject("misconception_id", UUID.class),
          MisconceptionEvidenceOutcome.valueOf(result.getString("outcome")),
          result.getString("policy_version"),
          instant(result.getObject("created_at", OffsetDateTime.class)));

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
