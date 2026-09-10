package io.ramals.learningplatform.assessment.misconceptiongraph;

import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The bounded, read-only reads M2-ADR-033 §5's query surface needs. {@code SELECT}-only: there is
 * no {@code insert} / {@code update} / {@code publish} method here, on purpose (prompt §21). It
 * reads the authoritative tables directly -- {@code core.learning_objective} / {@code
 * core.diagnostic_node} joined out to skill / curriculum / domain, {@code core.skill_prerequisite}
 * ({@code V003}), {@code core.misconception} ({@code V057}), and the two {@code V061} edge tables --
 * the same "read the table you need, never delegate through another table's writer repository"
 * choice {@code DiagnosticReportRepository} already made.
 *
 * <p><b>Bounded query count (M2-ADR-033 §5, prompt §12).</b> One call to
 * {@link MisconceptionGraphQueryService#graphFor} runs at most five statements -- one per method
 * here -- and never one per misconception: {@link #findPublishedRelationshipsForMisconceptions} and
 * {@link #findPublishedPrerequisiteLinksForMisconceptions} take the whole id set and bulk-match it
 * with {@code IN (...)}. The count is O(1) in the number of misconceptions, not O(n).
 *
 * <p>No traversal API: no {@code findAllPaths}, {@code shortestPath}, {@code breadthFirstSearch},
 * {@code rankNeighbors}, or {@code findReachableMisconceptions}. That is not Step 2 (prompt §21).
 */
@Repository
public class MisconceptionGraphQueryRepository {

  private final JdbcTemplate jdbcTemplate;

  public MisconceptionGraphQueryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // -- Q1: resolve the requested node to its curriculum context ---------------------------------

  /**
   * Resolves one authoritative node to its owning skill / curriculum version / domain, walking a
   * {@code SUB_CONCEPT} up through its parent {@code CONCEPT} (M2-ADR-026's two-level tree). Empty
   * when no row of that exact kind has that id -- a kind mismatch resolves to empty, never to the
   * wrong node.
   */
  public Optional<GraphTargetView> resolveTarget(MisconceptionGraphTarget target) {
    String sql = switch (target.kind()) {
      case LEARNING_OBJECTIVE -> """
          SELECT lo.id AS node_id, lo.objective_code AS label, lo.id AS objective_id,
                 s.id AS owning_skill_id, s.stable_code AS owning_skill_code,
                 sv.curriculum_version_id AS curriculum_version_id, d.code AS domain_code
          FROM core.learning_objective lo
          JOIN core.skill_version sv ON sv.id = lo.skill_version_id
          JOIN core.skill s ON s.id = sv.skill_id
          JOIN core.curriculum_version cv ON cv.id = sv.curriculum_version_id
          JOIN core.learning_domain d ON d.id = cv.domain_id
          WHERE lo.id = ?
          """;
      case CONCEPT -> """
          SELECT dn.id AS node_id, dn.name AS label, lo.id AS objective_id,
                 s.id AS owning_skill_id, s.stable_code AS owning_skill_code,
                 sv.curriculum_version_id AS curriculum_version_id, d.code AS domain_code
          FROM core.diagnostic_node dn
          JOIN core.learning_objective lo ON lo.id = dn.objective_id
          JOIN core.skill_version sv ON sv.id = lo.skill_version_id
          JOIN core.skill s ON s.id = sv.skill_id
          JOIN core.curriculum_version cv ON cv.id = sv.curriculum_version_id
          JOIN core.learning_domain d ON d.id = cv.domain_id
          WHERE dn.id = ? AND dn.node_type = 'CONCEPT'
          """;
      case SUB_CONCEPT -> """
          SELECT dn.id AS node_id, dn.name AS label, lo.id AS objective_id,
                 s.id AS owning_skill_id, s.stable_code AS owning_skill_code,
                 sv.curriculum_version_id AS curriculum_version_id, d.code AS domain_code
          FROM core.diagnostic_node dn
          JOIN core.diagnostic_node parent ON parent.id = dn.parent_node_id
          JOIN core.learning_objective lo ON lo.id = parent.objective_id
          JOIN core.skill_version sv ON sv.id = lo.skill_version_id
          JOIN core.skill s ON s.id = sv.skill_id
          JOIN core.curriculum_version cv ON cv.id = sv.curriculum_version_id
          JOIN core.learning_domain d ON d.id = cv.domain_id
          WHERE dn.id = ? AND dn.node_type = 'SUB_CONCEPT'
          """;
    };
    return jdbcTemplate.query(sql, (result, row) -> new GraphTargetView(
        target.kind(),
        result.getObject("node_id", UUID.class),
        result.getString("label"),
        result.getObject("objective_id", UUID.class),
        result.getObject("owning_skill_id", UUID.class),
        result.getString("owning_skill_code"),
        result.getObject("curriculum_version_id", UUID.class),
        result.getString("domain_code")),
        target.id()).stream().findFirst();
  }

  // -- Q2: the owning skill's existing curriculum prerequisites --------------------------------

  /**
   * The owning skill's {@code core.skill_prerequisite} rows for one curriculum version, straight
   * from the authoritative table (skill-to-skill, {@code V003}). Deterministically ordered by the
   * prerequisite skill's stable code.
   */
  public List<CurriculumPrerequisiteView> findCurriculumPrerequisites(
      UUID owningSkillId, UUID curriculumVersionId) {
    return jdbcTemplate.query("""
        SELECT s.id AS prerequisite_skill_id, s.stable_code AS prerequisite_skill_code
        FROM core.skill_prerequisite sp
        JOIN core.skill s ON s.id = sp.prerequisite_skill_id
        WHERE sp.curriculum_version_id = ? AND sp.skill_id = ?
        ORDER BY s.stable_code, s.id
        """, (result, row) -> new CurriculumPrerequisiteView(
            result.getObject("prerequisite_skill_id", UUID.class),
            result.getString("prerequisite_skill_code")),
        curriculumVersionId, owningSkillId);
  }

  // -- Q3: the PUBLISHED misconceptions targeting the node -------------------------------------

  /**
   * Every {@code PUBLISHED} misconception whose M2-ADR-026 exclusive arc points at <em>this exact
   * node</em> -- the objective arc for a {@code LEARNING_OBJECTIVE}, the diagnostic-node arc for a
   * {@code CONCEPT} / {@code SUB_CONCEPT}. No ancestor/descendant expansion (prompt §8). The
   * {@code status = 'PUBLISHED'} filter is in SQL, not applied in memory (prompt §7).
   */
  public List<PublishedMisconceptionView> findPublishedMisconceptionsForTarget(
      MisconceptionGraphTarget target) {
    String arcColumn = target.kind() == MisconceptionTargetType.LEARNING_OBJECTIVE
        ? "target_objective_id"
        : "target_diagnostic_node_id";
    return jdbcTemplate.query(
        "SELECT id, name, description FROM core.misconception "
            + "WHERE status = 'PUBLISHED' AND " + arcColumn + " = ? "
            + "ORDER BY name, id",
        (result, row) -> new PublishedMisconceptionView(
            result.getObject("id", UUID.class),
            result.getString("name"),
            result.getString("description"),
            target.kind(),
            target.id()),
        target.id());
  }

  // -- Q4: PUBLISHED MISCONCEPTION_RELATED edges with >= 1 endpoint in the set ------------------

  /**
   * Every {@code PUBLISHED} {@code core.misconception_relationship} edge with at least one endpoint
   * in {@code misconceptionIds} -- "among, and from, that set" (M2-ADR-033 §5). One bulk statement:
   * the id set is matched with {@code IN (...)} on both endpoint columns, never one query per
   * misconception. {@code sourceInScope} / {@code targetInScope} are computed against the same set;
   * no external misconception row is read.
   */
  public List<PublishedMisconceptionRelationshipView> findPublishedRelationshipsForMisconceptions(
      Set<UUID> misconceptionIds) {
    if (misconceptionIds.isEmpty()) {
      return List.of();
    }
    String placeholders = placeholders(misconceptionIds.size());
    String sql =
        "SELECT id, misconception_a_id, misconception_b_id, relationship_type, rationale, "
            + "published_at FROM core.misconception_relationship "
            + "WHERE status = 'PUBLISHED' "
            + "AND (misconception_a_id IN (" + placeholders + ") "
            + "OR misconception_b_id IN (" + placeholders + ")) "
            + "ORDER BY relationship_type, misconception_a_id, misconception_b_id";
    Object[] args = new Object[misconceptionIds.size() * 2];
    int i = 0;
    for (UUID id : misconceptionIds) {
      args[i] = id;
      args[i + misconceptionIds.size()] = id;
      i++;
    }
    return jdbcTemplate.query(sql, relationshipMapper(misconceptionIds), args);
  }

  // -- Q5: PUBLISHED MISCONCEPTION_PREREQUISITE_LINK edges for the set -------------------------

  /**
   * Every {@code PUBLISHED} {@code core.misconception_prerequisite_link} whose misconception is in
   * {@code misconceptionIds}, joined to its prerequisite skill's stable code. One bulk statement.
   * Deterministically ordered by misconception then prerequisite skill code.
   */
  public List<PublishedMisconceptionPrerequisiteLinkView>
      findPublishedPrerequisiteLinksForMisconceptions(Set<UUID> misconceptionIds) {
    if (misconceptionIds.isEmpty()) {
      return List.of();
    }
    String sql =
        "SELECT pl.id, pl.misconception_id, pl.prerequisite_skill_id, "
            + "s.stable_code AS prerequisite_skill_code, pl.rationale, pl.published_at "
            + "FROM core.misconception_prerequisite_link pl "
            + "JOIN core.skill s ON s.id = pl.prerequisite_skill_id "
            + "WHERE pl.status = 'PUBLISHED' "
            + "AND pl.misconception_id IN (" + placeholders(misconceptionIds.size()) + ") "
            + "ORDER BY pl.misconception_id, s.stable_code, pl.id";
    return jdbcTemplate.query(sql, PREREQUISITE_LINK_MAPPER, misconceptionIds.toArray());
  }

  // -- mappers / helpers ---------------------------------------------------------------------------

  private static RowMapper<PublishedMisconceptionRelationshipView> relationshipMapper(
      Set<UUID> inScope) {
    return (result, row) -> {
      UUID a = result.getObject("misconception_a_id", UUID.class);
      UUID b = result.getObject("misconception_b_id", UUID.class);
      MisconceptionRelatedType type =
          MisconceptionRelatedType.valueOf(result.getString("relationship_type"));
      return new PublishedMisconceptionRelationshipView(
          result.getObject("id", UUID.class),
          type,
          a,
          b,
          inScope.contains(a),
          inScope.contains(b),
          type.isSymmetric(),
          type == MisconceptionRelatedType.SPECIALISES ? "GENERALISES" : null,
          result.getString("rationale"),
          instant(result.getTimestamp("published_at")));
    };
  }

  private static final RowMapper<PublishedMisconceptionPrerequisiteLinkView> PREREQUISITE_LINK_MAPPER =
      (result, row) -> new PublishedMisconceptionPrerequisiteLinkView(
          result.getObject("id", UUID.class),
          result.getObject("misconception_id", UUID.class),
          result.getObject("prerequisite_skill_id", UUID.class),
          result.getString("prerequisite_skill_code"),
          result.getString("rationale"),
          instant(result.getTimestamp("published_at")));

  private static String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
