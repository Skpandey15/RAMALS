package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import io.ramals.learningplatform.assessment.ProbeRelationshipType;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The bounded, read-only reads {@link HypothesisUncertaintyContextAssembler} needs to turn
 * repository state into a {@link HypothesisUncertaintyContext} (M2-ADR-034 Amendment 1 §F). Read-only:
 * there is no insert/update method here, on purpose -- {@code HYPOTHESIS_UNCERTAINTY_V1} persists
 * nothing (Amendment 1 §M: no migration, no runtime wiring).
 *
 * <p>{@link #findPerInteractionEvidence} is deliberately narrower than {@code
 * DiagnosticConfidenceRepository#evidenceCounts}: that H5 read is scoped to {@code
 * assessmentVersionId} and spans every attempt the learner has made against it (H5's own
 * cross-attempt identity); this one is scoped to exactly one {@code attemptId} -- the tighter,
 * per-interaction boundary Amendment 1 §F requires. Neither reads nor writes the other's table.
 */
@Repository
public class HypothesisUncertaintyRepository {

  private final JdbcTemplate jdbcTemplate;

  public HypothesisUncertaintyRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Every {@code core.diagnostic_probe_provenance} row for this hypothesis tuple, scoped to exactly
   * one diagnostic interaction (attempt) -- never another attempt, however recent. Each row's own
   * already-written {@code core.assessment_response} is the raw scoring fact {@link
   * io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome#classify} turns into evidence;
   * classification happens in the assembler, not here.
   */
  public List<RawObservation> findPerInteractionEvidence(
      UUID attemptId, UUID sourceObjectiveId, UUID targetObjectiveId,
      ProbeRelationshipType relationshipType) {
    return jdbcTemplate.query("""
        SELECT p.id AS observation_id, ar.is_correct, iv.item_type
        FROM core.diagnostic_probe_provenance p
        JOIN core.assessment_response ar
          ON ar.attempt_id = p.attempt_id AND ar.item_version_id = p.item_version_id
        JOIN core.assessment_item_version iv ON iv.id = p.item_version_id
        WHERE p.attempt_id = ? AND p.source_objective_id = ? AND p.target_objective_id = ?
          AND p.relationship_type = ?
        """, (result, row) -> new RawObservation(
            result.getObject("observation_id", UUID.class),
            result.getBoolean("is_correct"),
            result.getString("item_type")),
        attemptId, sourceObjectiveId, targetObjectiveId, relationshipType.name());
  }

  /**
   * The curriculum domain code each of a batch of learning objectives belongs to, resolved through
   * its own {@code skill_version -> skill -> learning_domain} chain -- the same join shape {@code
   * DiagnosticReportRepository.findObjectiveContext} uses, read directly here since this is this
   * package's own concern, not delegated through another table's writer repository. An objective id
   * with no matching row is simply absent from the returned map.
   */
  public Map<UUID, String> findObjectiveDomainCodes(Collection<UUID> objectiveIds) {
    if (objectiveIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(objectiveIds.size(), "?"));
    List<Object[]> rows = jdbcTemplate.query(
        "SELECT lo.id AS objective_id, d.code AS domain_code "
            + "FROM core.learning_objective lo "
            + "JOIN core.skill_version sv ON sv.id = lo.skill_version_id "
            + "JOIN core.skill s ON s.id = sv.skill_id "
            + "JOIN core.learning_domain d ON d.id = s.domain_id "
            + "WHERE lo.id IN (" + placeholders + ")",
        (result, row) -> new Object[] {
            result.getObject("objective_id", UUID.class), result.getString("domain_code")},
        objectiveIds.toArray());
    Map<UUID, String> byObjectiveId = new HashMap<>();
    for (Object[] row : rows) {
      byObjectiveId.put((UUID) row[0], (String) row[1]);
    }
    return byObjectiveId;
  }

  /** One raw, unclassified probe-response fact -- see {@link #findPerInteractionEvidence}. */
  public record RawObservation(UUID observationId, boolean isCorrect, String itemType) {
  }
}
