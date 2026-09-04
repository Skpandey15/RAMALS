package io.ramals.learningplatform.curriculum;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class CurriculumRepository {

  private static final org.springframework.jdbc.core.RowMapper<VersionRow> VERSION_ROW_MAPPER =
      (result, row) -> new VersionRow(
          result.getObject("id", UUID.class),
          result.getString("code"),
          result.getString("version_code"),
          result.getString("status"));

  private final JdbcTemplate jdbcTemplate;

  public CurriculumRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<CurriculumGraph> findReadableGraph(String domainCode, String versionCode) {
    List<VersionRow> versions = jdbcTemplate.query("""
        SELECT cv.id, d.code, cv.version_code, cv.status
        FROM core.curriculum_version cv
        JOIN core.learning_domain d ON d.id = cv.domain_id
        WHERE d.code = ? AND cv.version_code = ? AND cv.status IN ('PUBLISHED', 'RETIRED')
        """, VERSION_ROW_MAPPER, domainCode, versionCode);
    return versions.stream().findFirst().map(this::buildGraph);
  }

  /**
   * Resolves a graph by the curriculum version's own identity rather than by
   * {@code (domainCode, versionCode)}. For a caller that already holds a
   * {@code curriculum_version_id} FK -- {@code assessment_version.curriculum_version_id}, say --
   * and would otherwise have no way to ask for its graph: the assessment version's own
   * {@code version_code} ("v1", "v2"...) is a different, unrelated versioning scheme from the
   * curriculum's, and passing one where the other belongs resolves the wrong thing or nothing at
   * all.
   */
  public Optional<CurriculumGraph> findReadableGraph(UUID curriculumVersionId) {
    List<VersionRow> versions = jdbcTemplate.query("""
        SELECT cv.id, d.code, cv.version_code, cv.status
        FROM core.curriculum_version cv
        JOIN core.learning_domain d ON d.id = cv.domain_id
        WHERE cv.id = ? AND cv.status IN ('PUBLISHED', 'RETIRED')
        """, VERSION_ROW_MAPPER, curriculumVersionId);
    return versions.stream().findFirst().map(this::buildGraph);
  }

  private CurriculumGraph buildGraph(VersionRow version) {
    List<MutableSkill> skills = jdbcTemplate.query("""
        SELECT sv.skill_id, s.stable_code, sv.title, sv.description, sv.difficulty,
               sv.target_proficiency, sv.estimated_learning_minutes, sv.mastery_threshold,
               sv.confidence_threshold, sv.required_evidence_count,
               sv.accepted_evidence_types, sv.required_difficulty_bands, sv.display_order
        FROM core.skill_version sv
        JOIN core.skill s ON s.id = sv.skill_id
        WHERE sv.curriculum_version_id = ?
        ORDER BY sv.display_order, s.stable_code
        """, (result, row) -> new MutableSkill(
            result.getObject("skill_id", UUID.class),
            result.getString("stable_code"),
            result.getString("title"),
            result.getString("description"),
            result.getString("difficulty"),
            result.getBigDecimal("target_proficiency"),
            result.getInt("estimated_learning_minutes"),
            result.getBigDecimal("mastery_threshold"),
            result.getBigDecimal("confidence_threshold"),
            result.getInt("required_evidence_count"),
            strings(result.getArray("accepted_evidence_types")),
            strings(result.getArray("required_difficulty_bands")),
            result.getInt("display_order")), version.id());

    Map<UUID, MutableSkill> byId = new HashMap<>();
    skills.forEach(skill -> byId.put(skill.skillId, skill));

    jdbcTemplate.query("""
        SELECT sv.skill_id, objective.objective_code, objective.description,
               objective.required, objective.display_order
        FROM core.learning_objective objective
        JOIN core.skill_version sv ON sv.id = objective.skill_version_id
        WHERE sv.curriculum_version_id = ?
        ORDER BY sv.skill_id, objective.display_order, objective.objective_code
        """, (RowCallbackHandler) result -> byId.get(result.getObject("skill_id", UUID.class)).objectives.add(
            new CurriculumGraph.Objective(
                result.getString("objective_code"),
                result.getString("description"),
                result.getBoolean("required"),
                result.getInt("display_order"))), version.id());

    jdbcTemplate.query("""
        SELECT edge.skill_id, prerequisite.stable_code
        FROM core.skill_prerequisite edge
        JOIN core.skill prerequisite ON prerequisite.id = edge.prerequisite_skill_id
        WHERE edge.curriculum_version_id = ?
        ORDER BY edge.skill_id, prerequisite.stable_code
        """, (RowCallbackHandler) result -> byId.get(result.getObject("skill_id", UUID.class))
            .prerequisites.add(result.getString("stable_code")), version.id());

    return new CurriculumGraph(
        version.id(), version.domainCode(), version.versionCode(), version.status(),
        skills.stream().map(MutableSkill::toNode).toList());
  }

  public Optional<PublishedSkillContext> findPublishedSkillContext(String skillCode) {
    return jdbcTemplate.query("""
        SELECT d.code AS domain_code, d.domain_type,
               (SELECT cv.version_code
                  FROM core.curriculum_version cv
                 WHERE cv.domain_id = d.id AND cv.status = 'PUBLISHED'
                 ORDER BY cv.published_at DESC LIMIT 1) AS version_code
          FROM core.skill s
          JOIN core.learning_domain d ON d.id = s.domain_id
         WHERE s.stable_code = ?
        """, (result, row) -> new PublishedSkillContext(
            result.getString("domain_code"), result.getString("domain_type"),
            result.getString("version_code")), skillCode).stream().findFirst();
  }

  public boolean hasPublishedCurriculum(UUID domainId) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT count(*) FROM core.curriculum_version
         WHERE domain_id = ? AND status = 'PUBLISHED'
        """, Integer.class, domainId);
    return count != null && count > 0;
  }

  private static List<String> strings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    return List.of((String[]) array.getArray());
  }

  private record VersionRow(UUID id, String domainCode, String versionCode, String status) {
  }

  private static final class MutableSkill {
    private final UUID skillId;
    private final String stableCode;
    private final String title;
    private final String description;
    private final String difficulty;
    private final java.math.BigDecimal targetProficiency;
    private final int estimatedLearningMinutes;
    private final java.math.BigDecimal masteryThreshold;
    private final java.math.BigDecimal confidenceThreshold;
    private final int requiredEvidenceCount;
    private final List<String> acceptedEvidenceTypes;
    private final List<String> requiredDifficultyBands;
    private final int displayOrder;
    private final List<CurriculumGraph.Objective> objectives = new ArrayList<>();
    private final List<String> prerequisites = new ArrayList<>();

    private MutableSkill(
        UUID skillId, String stableCode, String title, String description, String difficulty,
        java.math.BigDecimal targetProficiency, int estimatedLearningMinutes,
        java.math.BigDecimal masteryThreshold, java.math.BigDecimal confidenceThreshold,
        int requiredEvidenceCount, List<String> acceptedEvidenceTypes,
        List<String> requiredDifficultyBands, int displayOrder) {
      this.skillId = skillId;
      this.stableCode = stableCode;
      this.title = title;
      this.description = description;
      this.difficulty = difficulty;
      this.targetProficiency = targetProficiency;
      this.estimatedLearningMinutes = estimatedLearningMinutes;
      this.masteryThreshold = masteryThreshold;
      this.confidenceThreshold = confidenceThreshold;
      this.requiredEvidenceCount = requiredEvidenceCount;
      this.acceptedEvidenceTypes = acceptedEvidenceTypes;
      this.requiredDifficultyBands = requiredDifficultyBands;
      this.displayOrder = displayOrder;
    }

    private CurriculumGraph.SkillNode toNode() {
      return new CurriculumGraph.SkillNode(
          skillId, stableCode, title, description, difficulty, targetProficiency,
          estimatedLearningMinutes, masteryThreshold, confidenceThreshold,
          requiredEvidenceCount, acceptedEvidenceTypes, requiredDifficultyBands,
          displayOrder, List.copyOf(objectives), List.copyOf(prerequisites));
    }
  }
}
