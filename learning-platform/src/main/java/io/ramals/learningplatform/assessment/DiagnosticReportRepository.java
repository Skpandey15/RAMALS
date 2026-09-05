package io.ramals.learningplatform.assessment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M2-ADR-029 (H6): the batched, report-specific reads {@link DiagnosticReportService} needs and no
 * existing repository already exposes -- reading directly from {@code core.misconception}, {@code
 * core.diagnostic_node}, {@code core.learning_objective} (joined out to skill/curriculum/domain), and
 * {@code core.misconception_evidence_observation}, the same "read directly from the table you need,
 * never delegate through another table's own writer repository" choice {@code
 * MisconceptionConfidenceRepository} already made against {@code
 * core.misconception_evidence_observation} itself.
 *
 * <p>Read-only. Writes nothing, ever -- there is no {@code insert}/{@code publish} method here, on
 * purpose. Every method accepts a (possibly empty) batch of ids and returns in one round trip,
 * rather than one query per finding, so a report's own query count stays roughly constant in the
 * number of misconceptions it covers.
 */
@Repository
public class DiagnosticReportRepository {

  private final JdbcTemplate jdbcTemplate;

  public DiagnosticReportRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Every misconception this learner has ever produced {@code MISCONCEPTION_EVIDENCE_V1} evidence
   * for, anywhere, in any domain -- {@link DiagnosticReportService} filters this down to one domain
   * itself, since a misconception's own domain is resolved through its target, not stored on the
   * evidence row. */
  public List<UUID> findMisconceptionIdsWithEvidence(UUID learnerId) {
    if (learnerId == null) {
      return List.of();
    }
    return jdbcTemplate.query("""
        SELECT DISTINCT misconception_id
        FROM core.misconception_evidence_observation
        WHERE learner_id = ? AND policy_version = ?
        """, (result, row) -> result.getObject("misconception_id", UUID.class),
        learnerId, MisconceptionEvidenceCaptureService.POLICY);
  }

  /** One misconception's own authored facts and its exclusive-arc target -- exactly what {@code
   * core.misconception} itself stores, batched by id. */
  public List<MisconceptionContextRow> findMisconceptionContext(Collection<UUID> misconceptionIds) {
    if (misconceptionIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        "SELECT id, name, description, target_objective_id, target_diagnostic_node_id "
            + "FROM core.misconception WHERE id IN (" + placeholders(misconceptionIds.size()) + ")",
        (result, row) -> new MisconceptionContextRow(
            result.getObject("id", UUID.class),
            result.getString("name"),
            result.getString("description"),
            result.getObject("target_objective_id", UUID.class),
            result.getObject("target_diagnostic_node_id", UUID.class)),
        misconceptionIds.toArray());
  }

  /** One authored fact and identity: id, name, own type, own objective link, own parent link --
   * batched, used to walk a SUB_CONCEPT up to its CONCEPT and a CONCEPT up to its objective. */
  public List<DiagnosticNodeRow> findDiagnosticNodes(Collection<UUID> nodeIds) {
    if (nodeIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        "SELECT id, name, node_type, objective_id, parent_node_id FROM core.diagnostic_node "
            + "WHERE id IN (" + placeholders(nodeIds.size()) + ")",
        (result, row) -> new DiagnosticNodeRow(
            result.getObject("id", UUID.class),
            result.getString("name"),
            DiagnosticNodeType.valueOf(result.getString("node_type")),
            result.getObject("objective_id", UUID.class),
            result.getObject("parent_node_id", UUID.class)),
        nodeIds.toArray());
  }

  /** One objective's own code/description plus the domain its curriculum version belongs to -- the
   * one join this report needs that no existing repository exposes by objective id ({@code
   * CurriculumGraph}'s own {@code Objective} is addressed by code, not id). */
  public List<ObjectiveContextRow> findObjectiveContext(Collection<UUID> objectiveIds) {
    if (objectiveIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query("""
        SELECT lo.id AS objective_id, lo.objective_code, lo.description,
               sv.curriculum_version_id, d.code AS domain_code
        FROM core.learning_objective lo
        JOIN core.skill_version sv ON sv.id = lo.skill_version_id
        JOIN core.curriculum_version cv ON cv.id = sv.curriculum_version_id
        JOIN core.learning_domain d ON d.id = cv.domain_id
        WHERE lo.id IN (""" + placeholders(objectiveIds.size()) + ")",
        (result, row) -> new ObjectiveContextRow(
            result.getObject("objective_id", UUID.class),
            result.getString("objective_code"),
            result.getString("description"),
            result.getObject("curriculum_version_id", UUID.class),
            result.getString("domain_code")),
        objectiveIds.toArray());
  }

  /** The live {@code SUPPORTING}/{@code CONTRADICTORY}/{@code INCONCLUSIVE} counts for a batch of
   * misconceptions with no persisted G3 snapshot yet ({@code confidenceState = NOT_ASSESSED}) --
   * counting rows, never computing a band. */
  public List<EvidenceCountRow> findEvidenceCounts(UUID learnerId, Collection<UUID> misconceptionIds) {
    if (misconceptionIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        "SELECT misconception_id, outcome, count(*) AS row_count "
            + "FROM core.misconception_evidence_observation "
            + "WHERE learner_id = ? AND policy_version = ? "
            + "AND misconception_id IN (" + placeholders(misconceptionIds.size()) + ") "
            + "GROUP BY misconception_id, outcome",
        (result, row) -> new EvidenceCountRow(
            result.getObject("misconception_id", UUID.class),
            MisconceptionEvidenceOutcome.valueOf(result.getString("outcome")),
            result.getInt("row_count")),
        prepend(learnerId, MisconceptionEvidenceCaptureService.POLICY, misconceptionIds));
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private static Object[] prepend(Object first, Object second, Collection<UUID> rest) {
    Object[] args = new Object[rest.size() + 2];
    args[0] = first;
    args[1] = second;
    int i = 2;
    for (UUID id : rest) {
      args[i++] = id;
    }
    return args;
  }

  /** One {@code core.misconception} row's own authored facts and exclusive-arc target. */
  public record MisconceptionContextRow(
      UUID id, String name, String description, UUID targetObjectiveId, UUID targetDiagnosticNodeId) {
  }

  /** One {@code core.diagnostic_node} row's own identity, type, and parent links. */
  public record DiagnosticNodeRow(
      UUID id, String name, DiagnosticNodeType nodeType, UUID objectiveId, UUID parentNodeId) {
  }

  /** One {@code core.learning_objective} row's own code/description and the domain it belongs to. */
  public record ObjectiveContextRow(
      UUID objectiveId, String objectiveCode, String description, UUID curriculumVersionId,
      String domainCode) {
  }

  /** One (misconception, outcome) count, for a misconception with no persisted G3 snapshot. */
  public record EvidenceCountRow(UUID misconceptionId, MisconceptionEvidenceOutcome outcome, int count) {
  }
}
