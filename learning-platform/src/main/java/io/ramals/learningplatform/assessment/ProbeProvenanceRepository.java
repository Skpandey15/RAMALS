package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.observability.UuidV7;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * DIAGNOSTIC_SELECTION_V5 (M2-ADR-025): the only writer of {@code core.diagnostic_probe_provenance}.
 * Deliberately its own class, not a method on {@link ProbeRelationshipRepository} -- that repository
 * (and {@link ProbeRelationshipService} above it) is documented as read-only, no writer dependency
 * at all, per M2-ADR-024; this is the runtime capability that consumes it, and owns the one write
 * H4b's foundation was always going to need once something called it.
 */
@Repository
public class ProbeProvenanceRepository {

  private final JdbcTemplate jdbcTemplate;

  public ProbeProvenanceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Records why {@code selection.chosenItemVersionId()} was placed in {@code attemptId}'s packet.
   * Written inside the same transaction that inserts the attempt_item row it references, while the
   * attempt is still {@code IN_PROGRESS} -- the only state {@code trg_probe_provenance_guard}
   * admits an insert under.
   */
  public void insert(UUID attemptId, HypothesisDrivenProbeDiagnosticSelector.Selection selection) {
    DiagnosticHypothesis hypothesis = selection.hypothesis();
    jdbcTemplate.update("""
        INSERT INTO core.diagnostic_probe_provenance
          (id, attempt_id, item_version_id, source_attempt_id, source_item_version_id,
           source_objective_id, relationship_type, target_objective_id, authorizing_relationship_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, UuidV7.generate(), attemptId, selection.chosenItemVersionId(),
        selection.sourceAttemptId(), hypothesis.triggerItemVersionId(), hypothesis.triggerObjectiveId(),
        hypothesis.relationshipType().name(), hypothesis.targetObjectiveId(),
        hypothesis.authorizingRelationshipId());
  }

  private static final RowMapper<ProbeProvenance> PROVENANCE_MAPPER =
      (result, row) -> new ProbeProvenance(
          result.getObject("id", UUID.class),
          result.getObject("attempt_id", UUID.class),
          result.getObject("item_version_id", UUID.class),
          result.getObject("source_attempt_id", UUID.class),
          result.getObject("source_item_version_id", UUID.class),
          result.getObject("source_objective_id", UUID.class),
          ProbeRelationshipType.valueOf(result.getString("relationship_type")),
          result.getObject("target_objective_id", UUID.class),
          result.getObject("authorizing_relationship_id", UUID.class));

  /** Reads back one probe's provenance, for audit. */
  public Optional<ProbeProvenance> findByAttemptAndItem(UUID attemptId, UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT id, attempt_id, item_version_id, source_attempt_id, source_item_version_id,
               source_objective_id, relationship_type, target_objective_id, authorizing_relationship_id
        FROM core.diagnostic_probe_provenance
        WHERE attempt_id = ? AND item_version_id = ?
        """, PROVENANCE_MAPPER, attemptId, itemVersionId).stream().findFirst();
  }

  /**
   * Every provenance row for one attempt -- the packet's own real, final hypothesis-driven-probe
   * selection, whether {@code DIAGNOSTIC_SELECTION_V6} itself activated and chose it, or {@code V6}
   * fell back and {@code V5}'s own {@code resolveHypothesisProbeSelection} chose it (M2-ADR-034
   * Amendment 4 sec S: this record is already exact and immutable either way, and is never
   * recomputed by re-running either selector's own discovery).
   *
   * <p>Returns a list rather than {@code Optional} deliberately: the database schema itself does not
   * declare {@code UNIQUE(attempt_id)}, only {@code UNIQUE(attempt_id, item_version_id)} -- at most
   * one row per attempt is an application-level packet-composition invariant
   * ({@code HypothesisDrivenProbeDiagnosticSelector.MAX_HYPOTHESIS_PROBES_PER_PACKET == 1}), not a
   * schema guarantee, so a caller finding more than one row here has found a genuine corruption, not
   * a bug in this method.
   */
  public List<ProbeProvenance> findByAttempt(UUID attemptId) {
    return jdbcTemplate.query("""
        SELECT id, attempt_id, item_version_id, source_attempt_id, source_item_version_id,
               source_objective_id, relationship_type, target_objective_id, authorizing_relationship_id
        FROM core.diagnostic_probe_provenance
        WHERE attempt_id = ?
        """, PROVENANCE_MAPPER, attemptId);
  }
}
