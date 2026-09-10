package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and authors the two M2-ADR-033 misconception graph tables. JdbcTemplate, the same style
 * {@code MisconceptionRepository} (V057) and {@code JdbcDiagnosticProbeProposalDecisionRepository}
 * (V060) use.
 *
 * <p>Step 1 scope only (M2-ADR-033 §22): persist an authored edge, publish it, fetch by id,
 * determine duplicate existence, validate endpoints, and support {@code SPECIALISES} cycle
 * checking and the prerequisite-link curriculum check. It builds <b>no</b> traversal/query API
 * (no {@code findAllPaths}, {@code shortestPath}, {@code rankRelatedMisconceptions},
 * {@code diagnosticNeighborsForLearner}) -- that is Step 2. The database's own triggers and
 * constraints remain the final authority; every method here is trusted from the database, not a
 * substitute for it.
 */
@Repository
public class MisconceptionRelationshipRepository {

  /** Result of checking a prerequisite link's far endpoint against the curriculum graph. */
  public enum PrerequisiteCheck {
    /** A matching {@code core.skill_prerequisite} row exists for the owning skill and version. */
    MATCH,
    /** The referenced skill is not a curriculum prerequisite of the misconception's owning skill. */
    NOT_A_PREREQUISITE,
    /** The misconception's target arc does not reach a learning objective. */
    OWNING_SKILL_UNRESOLVABLE
  }

  private final JdbcTemplate jdbcTemplate;

  public MisconceptionRelationshipRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // -- core.misconception_relationship --------------------------------------------------------------

  /**
   * Inserts a DRAFT related edge with the supplied canonical {@code (a, b)} order and returns its
   * generated id. The database re-checks self-edge, canonical ordering, the relationship-type set,
   * and {@code SPECIALISES} acyclicity.
   */
  public UUID insertDraftRelationship(
      CanonicalMisconceptionPair pair, MisconceptionRelatedType relatedType, String rationale) {
    UUID id = io.ramals.learningplatform.observability.UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.misconception_relationship
          (id, misconception_a_id, misconception_b_id, relationship_type, status, rationale)
        VALUES (?, ?, ?, ?, 'DRAFT', ?)
        """, id, pair.a(), pair.b(), relatedType.name(), rationale);
    return id;
  }

  /** DRAFT -> PUBLISHED. Immutable afterward -- see {@code trg_misconception_relationship_guard}. */
  public void publishRelationship(UUID id) {
    jdbcTemplate.update(
        "UPDATE core.misconception_relationship SET status = 'PUBLISHED' WHERE id = ?", id);
  }

  public Optional<MisconceptionRelationship> findRelationshipById(UUID id) {
    return jdbcTemplate.query("""
        SELECT id, misconception_a_id, misconception_b_id, relationship_type, status, rationale,
               created_at, published_at
        FROM core.misconception_relationship
        WHERE id = ?
        """, RELATIONSHIP_MAPPER, id).stream().findFirst();
  }

  /** Whether a logically identical related edge (canonical pair + type) already exists. */
  public boolean relationshipExists(
      CanonicalMisconceptionPair pair, MisconceptionRelatedType relatedType) {
    Boolean exists = jdbcTemplate.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM core.misconception_relationship
          WHERE misconception_a_id = ? AND misconception_b_id = ? AND relationship_type = ?
        )
        """, Boolean.class, pair.a(), pair.b(), relatedType.name());
    return Boolean.TRUE.equals(exists);
  }

  /**
   * Whether adding {@code a SPECIALISES b} would close a direct or transitive cycle -- i.e. whether
   * {@code b} can already reach {@code a} by following {@code SPECIALISES} edges. Mirrors the
   * database trigger's own recursive check, for a deterministic pre-persist reason code.
   */
  public boolean wouldSpecialisationCreateCycle(UUID a, UUID b) {
    Boolean cycle = jdbcTemplate.queryForObject("""
        SELECT EXISTS (
          WITH RECURSIVE reachable(misconception_id) AS (
            SELECT ?::uuid
            UNION
            SELECT edge.misconception_b_id
            FROM core.misconception_relationship edge
            JOIN reachable current_node ON edge.misconception_a_id = current_node.misconception_id
            WHERE edge.relationship_type = 'SPECIALISES'
          )
          SELECT 1 FROM reachable WHERE misconception_id = ?::uuid
        )
        """, Boolean.class, b, a);
    return Boolean.TRUE.equals(cycle);
  }

  // -- core.misconception_prerequisite_link -------------------------------------------------------

  /** Inserts a DRAFT prerequisite link and returns its generated id. */
  public UUID insertDraftPrerequisiteLink(
      UUID misconceptionId, UUID prerequisiteSkillId, String rationale) {
    UUID id = io.ramals.learningplatform.observability.UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.misconception_prerequisite_link
          (id, misconception_id, prerequisite_skill_id, status, rationale)
        VALUES (?, ?, ?, 'DRAFT', ?)
        """, id, misconceptionId, prerequisiteSkillId, rationale);
    return id;
  }

  /** DRAFT -> PUBLISHED. The trigger re-runs the full publish-time validation on this update. */
  public void publishPrerequisiteLink(UUID id) {
    jdbcTemplate.update(
        "UPDATE core.misconception_prerequisite_link SET status = 'PUBLISHED' WHERE id = ?", id);
  }

  public Optional<MisconceptionPrerequisiteLink> findPrerequisiteLinkById(UUID id) {
    return jdbcTemplate.query("""
        SELECT id, misconception_id, prerequisite_skill_id, status, rationale, created_at,
               published_at
        FROM core.misconception_prerequisite_link
        WHERE id = ?
        """, PREREQUISITE_LINK_MAPPER, id).stream().findFirst();
  }

  public boolean prerequisiteLinkExists(UUID misconceptionId, UUID prerequisiteSkillId) {
    Boolean exists = jdbcTemplate.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM core.misconception_prerequisite_link
          WHERE misconception_id = ? AND prerequisite_skill_id = ?
        )
        """, Boolean.class, misconceptionId, prerequisiteSkillId);
    return Boolean.TRUE.equals(exists);
  }

  /**
   * Mirrors {@code trg_misconception_prerequisite_link_guard}'s publish-time check: resolves the
   * misconception's owning {@code (skill, curriculum_version)} through its exclusive-arc target
   * (a direct objective, a CONCEPT's objective, or a SUB_CONCEPT's parent CONCEPT's objective), and
   * asserts a matching {@code core.skill_prerequisite} row exists.
   */
  public PrerequisiteCheck checkCurriculumPrerequisite(
      UUID misconceptionId, UUID prerequisiteSkillId) {
    var owning = jdbcTemplate.query("""
        SELECT sv.skill_id, sv.curriculum_version_id
        FROM core.misconception m
        LEFT JOIN core.diagnostic_node dn ON dn.id = m.target_diagnostic_node_id
        LEFT JOIN core.diagnostic_node dnp ON dnp.id = dn.parent_node_id
        JOIN core.learning_objective lo
          ON lo.id = COALESCE(m.target_objective_id, dn.objective_id, dnp.objective_id)
        JOIN core.skill_version sv ON sv.id = lo.skill_version_id
        WHERE m.id = ?
        """, (result, row) -> new UUID[] {
            result.getObject("skill_id", UUID.class),
            result.getObject("curriculum_version_id", UUID.class)
        }, misconceptionId).stream().findFirst();

    if (owning.isEmpty()) {
      return PrerequisiteCheck.OWNING_SKILL_UNRESOLVABLE;
    }
    Boolean match = jdbcTemplate.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM core.skill_prerequisite
          WHERE curriculum_version_id = ? AND skill_id = ? AND prerequisite_skill_id = ?
        )
        """, Boolean.class, owning.get()[1], owning.get()[0], prerequisiteSkillId);
    return Boolean.TRUE.equals(match)
        ? PrerequisiteCheck.MATCH
        : PrerequisiteCheck.NOT_A_PREREQUISITE;
  }

  // -- endpoint reads shared by the validator ---------------------------------------------------

  /** The lifecycle status of a {@code core.misconception}, or empty when no such row exists. */
  public Optional<MisconceptionGraphStatus> misconceptionStatus(UUID misconceptionId) {
    return jdbcTemplate.query(
        "SELECT status FROM core.misconception WHERE id = ?",
        (result, row) -> MisconceptionGraphStatus.valueOf(result.getString("status")),
        misconceptionId).stream().findFirst();
  }

  public boolean skillExists(UUID skillId) {
    Boolean exists = jdbcTemplate.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM core.skill WHERE id = ?)", Boolean.class, skillId);
    return Boolean.TRUE.equals(exists);
  }

  // -- mappers -------------------------------------------------------------------------------------

  private static final RowMapper<MisconceptionRelationship> RELATIONSHIP_MAPPER =
      (result, row) -> new MisconceptionRelationship(
          result.getObject("id", UUID.class),
          result.getObject("misconception_a_id", UUID.class),
          result.getObject("misconception_b_id", UUID.class),
          MisconceptionRelatedType.valueOf(result.getString("relationship_type")),
          MisconceptionGraphStatus.valueOf(result.getString("status")),
          result.getString("rationale"),
          instant(result.getTimestamp("created_at")),
          instant(result.getTimestamp("published_at")));

  private static final RowMapper<MisconceptionPrerequisiteLink> PREREQUISITE_LINK_MAPPER =
      (result, row) -> new MisconceptionPrerequisiteLink(
          result.getObject("id", UUID.class),
          result.getObject("misconception_id", UUID.class),
          result.getObject("prerequisite_skill_id", UUID.class),
          MisconceptionGraphStatus.valueOf(result.getString("status")),
          result.getString("rationale"),
          instant(result.getTimestamp("created_at")),
          instant(result.getTimestamp("published_at")));

  private static Instant instant(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
