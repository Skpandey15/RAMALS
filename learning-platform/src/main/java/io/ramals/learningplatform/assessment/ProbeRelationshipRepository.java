package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * H4b foundation (M2-ADR-024): read-only access to the facts {@link ProbeRelationshipResolver}
 * needs, resolved from whichever table is authoritative for each {@link ProbeRelationshipType} --
 * never a second, independently-editable copy of curriculum or objective-tagging facts. See the
 * ADR's §1 for why {@code SAME_OBJECTIVE_CONFIRMATION}/{@code PREREQUISITE_VALIDATION} read existing
 * tables while {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK} read the one new table this
 * foundation adds.
 *
 * <p>Every method here picks at most one deterministic target objective per call --
 * {@link ProbeTargetObjective}'s own javadoc records why fanning out across several is out of scope
 * for this foundation.
 */
@Repository
public class ProbeRelationshipRepository {

  private static final String SCOREABLE_TYPES = "('SINGLE_CHOICE', 'FILL_BLANK')";

  private final JdbcTemplate jdbcTemplate;

  public ProbeRelationshipRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** The assessment version a trigger item belongs to -- target items are scoped to the same
   * version, the same per-attempt pool boundary {@code DiagnosticService} already reads from. */
  public Optional<UUID> assessmentVersionIdForItem(UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT assessment_version_id FROM core.assessment_item_version WHERE id = ?
        """, (result, row) -> result.getObject("assessment_version_id", UUID.class), itemVersionId)
        .stream().findFirst();
  }

  /** The trigger item's own objective. An item tagged to more than one objective is not exercised
   * by any content this platform has ever authored; the first by {@code objective_id} is taken
   * deterministically rather than the query failing on a shape nothing produces today. */
  public Optional<UUID> objectiveIdForItem(UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT objective_id FROM core.assessment_item_objective
        WHERE item_version_id = ? ORDER BY objective_id LIMIT 1
        """, (result, row) -> result.getObject("objective_id", UUID.class), itemVersionId)
        .stream().findFirst();
  }

  /** {@code SAME_OBJECTIVE_CONFIRMATION}'s target is always the trigger's own objective -- trivially
   * "defined" once the trigger has one at all; whether anything else is tagged to it is an items
   * question, not a relationship-existence question. */
  public ProbeTargetObjective sameObjectiveTarget(UUID triggerObjectiveId) {
    return new ProbeTargetObjective(triggerObjectiveId, null);
  }

  /**
   * {@code PREREQUISITE_VALIDATION}'s target: the trigger skill's first curriculum prerequisite
   * (ordered by that prerequisite's own {@code skill_version.display_order}, the same tie-break the
   * curriculum's own presentation order already uses), then that prerequisite's first required
   * objective ({@code display_order}). Empty if the trigger's skill has no prerequisite in this
   * curriculum version, or none of its objectives are required -- neither of which the curriculum
   * graph validator lets a published version reach, but this stays defensive rather than assuming
   * it.
   */
  public Optional<ProbeTargetObjective> prerequisiteValidationTarget(UUID triggerObjectiveId) {
    return jdbcTemplate.query("""
        SELECT po.id AS prerequisite_objective_id
        FROM core.learning_objective trigger_lo
        JOIN core.skill_version trigger_sv ON trigger_sv.id = trigger_lo.skill_version_id
        JOIN core.skill_prerequisite sp
          ON sp.skill_id = trigger_sv.skill_id
         AND sp.curriculum_version_id = trigger_sv.curriculum_version_id
        JOIN core.skill_version prerequisite_sv
          ON prerequisite_sv.skill_id = sp.prerequisite_skill_id
         AND prerequisite_sv.curriculum_version_id = trigger_sv.curriculum_version_id
        JOIN core.learning_objective po
          ON po.skill_version_id = prerequisite_sv.id AND po.required
        WHERE trigger_lo.id = ?
        ORDER BY prerequisite_sv.display_order, po.display_order
        LIMIT 1
        """, (result, row) -> new ProbeTargetObjective(
            result.getObject("prerequisite_objective_id", UUID.class), null),
        triggerObjectiveId).stream().findFirst();
  }

  /** {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK}'s target: the first {@code PUBLISHED}
   * {@code core.diagnostic_probe_relationship} row of the given type from the trigger objective,
   * ordered by {@code id} for a fixed, repeatable choice among several. */
  public Optional<ProbeTargetObjective> authoredRelationshipTarget(
      UUID triggerObjectiveId, ProbeRelationshipType relationshipType) {
    return jdbcTemplate.query("""
        SELECT id, target_objective_id FROM core.diagnostic_probe_relationship
        WHERE source_objective_id = ? AND relationship_type = ? AND status = 'PUBLISHED'
        ORDER BY id
        LIMIT 1
        """, (result, row) -> new ProbeTargetObjective(
            result.getObject("target_objective_id", UUID.class), result.getObject("id", UUID.class)),
        triggerObjectiveId, relationshipType.name()).stream().findFirst();
  }

  /** Every verified, scoreable item tagged to {@code objectiveId} within {@code assessmentVersionId},
   * excluding the trigger item itself, deterministically ordered. Exposure is not filtered here --
   * {@link ProbeRelationshipResolver} applies that, purely, against the caller's own exposure set. */
  public List<ProbeCandidateItem> itemsForObjective(
      UUID assessmentVersionId, UUID objectiveId, UUID excludeItemVersionId) {
    return jdbcTemplate.query("""
        SELECT iv.id, lin.logical_item_id
        FROM core.assessment_item_version iv
        JOIN core.assessment_item_lineage lin ON lin.item_version_id = iv.id
        JOIN core.assessment_item_objective aio ON aio.item_version_id = iv.id
        WHERE iv.assessment_version_id = ? AND aio.objective_id = ?
          AND iv.id <> ? AND iv.trust_state = 'VERIFIED_CONTENT'
          AND iv.item_type IN """ + SCOREABLE_TYPES + """

        ORDER BY iv.display_order, iv.item_code
        """, ITEM_MAPPER, assessmentVersionId, objectiveId, excludeItemVersionId);
  }

  /** The {@code is_correct}/{@code item_type} pair a probe answer was actually scored with, the raw
   * fact {@link HypothesisEvidenceOutcome#classify} interprets -- never itself the evidence model. */
  public Optional<ScoredProbeResponse> scoredProbeResponse(UUID attemptId, UUID probeItemVersionId) {
    return jdbcTemplate.query("""
        SELECT ar.is_correct, iv.item_type
        FROM core.assessment_response ar
        JOIN core.assessment_item_version iv ON iv.id = ar.item_version_id
        WHERE ar.attempt_id = ? AND ar.item_version_id = ?
        """, (result, row) -> new ScoredProbeResponse(
            result.getBoolean("is_correct"), result.getString("item_type")),
        attemptId, probeItemVersionId).stream().findFirst();
  }

  private static final RowMapper<ProbeCandidateItem> ITEM_MAPPER = (result, row) -> new ProbeCandidateItem(
      result.getObject("id", UUID.class), result.getObject("logical_item_id", UUID.class));

  /** The raw scoring fact behind one probe response -- see {@link #scoredProbeResponse}. */
  public record ScoredProbeResponse(boolean isCorrect, String itemType) {
  }
}
