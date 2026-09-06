package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M2-ADR-030 (H7): the batched, projection-specific reads {@link LongitudinalEvidenceService} needs.
 * Read-only; writes nothing, ever. Deliberately self-contained rather than reusing {@code
 * DiagnosticReportRepository}'s own ontology-context methods -- H6 was just stabilized (PR #257), and
 * this milestone's own architectural boundary is "H6 untouched, zero shared surface": the small
 * duplication of {@link #findMisconceptionContext}/{@link #findDiagnosticNodes}/{@link
 * #findObjectiveContext} below (structurally identical to, but a wholly separate type from, {@code
 * DiagnosticReportRepository}'s own) is preferred over any dependency that could let a future H6
 * change silently ripple into H7, or vice versa. A shared resolver may be extracted later, in its own
 * dedicated refactoring PR, once both milestones have settled.
 *
 * <p>Every method accepts a (possibly empty) batch of ids and returns in one round trip, so a
 * report's own query count stays roughly constant in the number of misconceptions it covers -- the
 * same discipline {@code DiagnosticReportRepository} already established.
 */
@Repository
public class LongitudinalEvidenceRepository {

  private final JdbcTemplate jdbcTemplate;

  public LongitudinalEvidenceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * The deterministically selected baseline row, per misconception, for every misconception this
   * learner has at least one directional ({@code supporting_count + contradictory_count > 0}) snapshot
   * for -- H7's own baseline-selection query (M2-ADR-030 §C). A misconception whose only snapshots are
   * {@code INSUFFICIENT_EVIDENCE} (both counts zero) is correctly absent from this result -- no
   * directional content to fix a boundary around.
   *
   * <p><b>{@code ORDER BY misconception_id, created_at ASC, id ASC} selects a governed deterministic
   * evidentiary anchor, never a claim of causal/generation-first ordering.</b> Ascending, not H6/G3's
   * own {@code DESC} "latest first" convention, since H7 wants the first-under-this-ordering row -- but
   * the same {@code id} (UuidV7) deterministic tiebreak, since {@code created_at} is fixed for an
   * entire transaction in PostgreSQL and so cannot alone distinguish two snapshots written by the same
   * submission. {@code id}'s own tiebreak bits are drawn from {@code SecureRandom} on every call
   * ({@link io.ramals.learningplatform.observability.UuidV7#generate}), with no monotonic counter, so
   * this ordering cannot prove which of two same-instant eligible snapshots was truly generated first
   * -- it only guarantees the same row is selected on every repeated read. Every downstream H7
   * classification is order-independent once this anchor is fixed, so this does not weaken H7's own
   * semantics (M2-ADR-030 §C, corrected on review of PR #258).
   */
  public List<MisconceptionConfidenceObservation> findEarliestBaselineSnapshotsForLearner(
      UUID learnerId) {
    return jdbcTemplate.query("""
        SELECT id, attempt_id, learner_id, misconception_id, supporting_count, contradictory_count,
               inconclusive_count, band, policy_version, created_at
        FROM (
          SELECT DISTINCT ON (misconception_id) id, attempt_id, learner_id, misconception_id,
                 supporting_count, contradictory_count, inconclusive_count, band, policy_version,
                 created_at
          FROM core.misconception_confidence_observation
          WHERE learner_id = ?
            AND (supporting_count + contradictory_count) > 0
          ORDER BY misconception_id, created_at ASC, id ASC
        ) earliest
        """, OBSERVATION_MAPPER, learnerId);
  }

  /**
   * Every {@code core.misconception_evidence_observation} row for a batch of misconceptions, for one
   * learner -- raw ids and outcomes, not pre-aggregated counts, because H7 must exclude the exact ids
   * a baseline snapshot already cites before counting what remains (a {@code GROUP BY} cannot express
   * that exclusion). {@code created_at} is included for the detail endpoint's own presentation
   * ordering (M2-ADR-030 §5) -- a deterministic display order only, never a causal one.
   */
  public List<EvidenceObservationRow> findAllEvidenceForLearner(
      UUID learnerId, Collection<UUID> misconceptionIds) {
    if (misconceptionIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        "SELECT id, misconception_id, outcome, created_at "
            + "FROM core.misconception_evidence_observation "
            + "WHERE learner_id = ? AND policy_version = ? "
            + "AND misconception_id IN (" + placeholders(misconceptionIds.size()) + ")",
        (result, row) -> new EvidenceObservationRow(
            result.getObject("id", UUID.class),
            result.getObject("misconception_id", UUID.class),
            MisconceptionEvidenceOutcome.valueOf(result.getString("outcome")),
            instant(result.getObject("created_at", OffsetDateTime.class))),
        prepend(learnerId, MisconceptionEvidenceCaptureService.POLICY, misconceptionIds));
  }

  /** One {@code core.misconception} row's own authored facts and exclusive-arc target -- H7-local,
   * structurally identical to {@code DiagnosticReportRepository.MisconceptionContextRow} but a
   * separate type (see class javadoc). */
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

  /** One {@code core.diagnostic_node} row's own identity, type, and parent links -- H7-local. */
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

  /** One objective's own code/description plus the domain its curriculum version belongs to --
   * H7-local. */
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

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static final org.springframework.jdbc.core.RowMapper<MisconceptionConfidenceObservation>
      OBSERVATION_MAPPER = (result, row) -> new MisconceptionConfidenceObservation(
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

  /** One {@code core.misconception_evidence_observation} row's own id, misconception, outcome, and
   * {@code created_at} -- H7's own evidence-set shape (needs the row id for the {@code E_post}
   * provenance-set difference, and {@code created_at} for the detail endpoint's presentation
   * ordering; H6's own {@code EvidenceCountRow} only ever needed pre-aggregated counts, never ids). */
  public record EvidenceObservationRow(
      UUID id, UUID misconceptionId, MisconceptionEvidenceOutcome outcome, Instant createdAt) {
  }

  /** H7-local: one {@code core.misconception} row's own authored facts and exclusive-arc target. */
  public record MisconceptionContextRow(
      UUID id, String name, String description, UUID targetObjectiveId, UUID targetDiagnosticNodeId) {
  }

  /** H7-local: one {@code core.diagnostic_node} row's own identity, type, and parent links. */
  public record DiagnosticNodeRow(
      UUID id, String name, DiagnosticNodeType nodeType, UUID objectiveId, UUID parentNodeId) {
  }

  /** H7-local: one {@code core.learning_objective} row's own code/description and its domain. */
  public record ObjectiveContextRow(
      UUID objectiveId, String objectiveCode, String description, UUID curriculumVersionId,
      String domainCode) {
  }
}
