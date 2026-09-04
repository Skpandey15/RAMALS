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
 * <p><b>Every target-objective query returns every candidate it finds, never just the first.</b>
 * None of them {@code LIMIT 1} -- picking one candidate over another when more than one exists is a
 * diagnostic-policy decision this foundation has no authority to make silently (M2-ADR-024's
 * amendment), so the full list is handed to {@link ProbeRelationshipResolver}, which reports
 * {@link ProbeResolutionOutcome#AMBIGUOUS_TARGET_OBJECTIVE} rather than choosing.
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

  /** Every objective the trigger item is tagged to, deterministically ordered by
   * {@code objective_id}. No content this platform has ever authored tags one item to more than one
   * objective, but this returns every one found rather than assuming that -- an item tagged to more
   * than one objective has no single trigger objective to resolve a hypothesis from at all, and
   * {@link ProbeRelationshipService} fails closed on that shape; see
   * {@link TriggerItemHasAmbiguousObjectiveException}. */
  public List<UUID> objectiveIdsForItem(UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT objective_id FROM core.assessment_item_objective
        WHERE item_version_id = ? ORDER BY objective_id
        """, (result, row) -> result.getObject("objective_id", UUID.class), itemVersionId);
  }

  /** {@code SAME_OBJECTIVE_CONFIRMATION}'s only possible target is the trigger's own objective --
   * trivially "defined" once the trigger has exactly one (already established by the caller before
   * this is invoked); whether anything else is tagged to it is an items question, not a
   * relationship-existence question, so this always returns exactly one candidate. */
  public List<ProbeTargetObjective> sameObjectiveTargets(UUID triggerObjectiveId) {
    return List.of(new ProbeTargetObjective(triggerObjectiveId, null));
  }

  /**
   * {@code PREREQUISITE_VALIDATION}'s candidates: every required objective of every one of the
   * trigger skill's curriculum prerequisites, deterministically ordered (prerequisite
   * {@code skill_version.display_order}, then that prerequisite's own objective
   * {@code display_order}) but not truncated to one -- a skill with more than one prerequisite, or a
   * prerequisite with more than one required objective, both surface as more than one row here, and
   * {@link ProbeRelationshipResolver} reports the ambiguity rather than this query picking one.
   */
  public List<ProbeTargetObjective> prerequisiteValidationTargets(UUID triggerObjectiveId) {
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
        """, (result, row) -> new ProbeTargetObjective(
            result.getObject("prerequisite_objective_id", UUID.class), null),
        triggerObjectiveId);
  }

  /** {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK}'s candidates: every {@code PUBLISHED}
   * {@code core.diagnostic_probe_relationship} row of the given type from the trigger objective,
   * ordered by {@code id} -- not truncated to one. The schema's own uniqueness constraint is on
   * {@code (source_objective_id, target_objective_id, relationship_type)}, not
   * {@code (source_objective_id, relationship_type)}, so more than one published row from the same
   * source is a shape the schema explicitly permits, and this returns all of them. */
  public List<ProbeTargetObjective> authoredRelationshipTargets(
      UUID triggerObjectiveId, ProbeRelationshipType relationshipType) {
    return jdbcTemplate.query("""
        SELECT id, target_objective_id FROM core.diagnostic_probe_relationship
        WHERE source_objective_id = ? AND relationship_type = ? AND status = 'PUBLISHED'
        ORDER BY id
        """, (result, row) -> new ProbeTargetObjective(
            result.getObject("target_objective_id", UUID.class), result.getObject("id", UUID.class)),
        triggerObjectiveId, relationshipType.name());
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
