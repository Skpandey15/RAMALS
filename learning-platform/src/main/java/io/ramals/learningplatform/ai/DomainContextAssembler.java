package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import io.ramals.learningplatform.ai.contract.GoalType;
import io.ramals.learningplatform.ai.contract.LearningGoalContext;
import io.ramals.learningplatform.learner.LearnerGoal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves the domain and goal context that accompanies a request to the AI execution plane.
 *
 * <p>The reason this is a component reading the database, rather than three literals in a builder,
 * is that an optional field nobody populates is not future-proofing — it is a migration deferred
 * and disguised. If {@code domainType} says {@code TECHNOLOGY} because a developer typed it, the
 * boundary has learned nothing; if it says {@code TECHNOLOGY} because {@code core.learning_domain}
 * says so, the boundary is genuinely domain-neutral and can be proven so.
 *
 * <p>Everything here is derived from a skill code. A skill belongs to exactly one domain, and that
 * domain is the subject of the request — which means an agent never has to be told which domain it
 * is working in, and cannot be told a domain that contradicts the skill.
 */
@Component
public class DomainContextAssembler {

  private final JdbcTemplate jdbcTemplate;

  public DomainContextAssembler(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Builds the domain context for a skill.
   *
   * <p>The curriculum version is the most recently published one for that domain. A retired version
   * is not offered: it described the curriculum a learner may have studied under, but it is not what
   * the platform teaches now, and an agent grounding itself in a retired version would be reasoning
   * from a syllabus the platform has withdrawn.
   *
   * @return empty when the skill code is unknown, so a caller cannot silently send a request about
   *     a domain that does not exist.
   */
  public Optional<DomainContext> forSkill(String skillCode) {
    if (skillCode == null || skillCode.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        SELECT d.code AS domain_code,
               d.domain_type,
               (SELECT cv.version_code
                  FROM core.curriculum_version cv
                 WHERE cv.domain_id = d.id AND cv.status = 'PUBLISHED'
                 ORDER BY cv.published_at DESC
                 LIMIT 1) AS version_code
          FROM core.skill s
          JOIN core.learning_domain d ON d.id = s.domain_id
         WHERE s.stable_code = ?
        """,
        (result, row) -> new DomainContext(
            result.getString("domain_code"),
            DomainType.valueOf(result.getString("domain_type")),
            result.getString("version_code")),
        skillCode)
        .stream()
        .findFirst();
  }

  /**
   * Builds the goal context for a learner's active goal, when one is set.
   *
   * <p>Today every goal is a {@link GoalType#LEARNING_DOMAIN} goal, because that is the only shape
   * the authoritative learner model stores. The type is stated explicitly rather than left implicit
   * so that when a second shape appears, the agents consuming this are already branching on a goal
   * type instead of assuming the code is a domain.
   */
  public Optional<LearningGoalContext> forLearnerGoal(Optional<LearnerGoal> goal) {
    return goal.map(active -> new LearningGoalContext(
        GoalType.LEARNING_DOMAIN,
        active.targetDomainCode(),
        active.targetDate(),
        null));
  }

  /**
   * Whether a domain has any published curriculum version.
   *
   * <p>Exposed because "no published version" is a legitimate state that produces a context with a
   * null {@code curriculumVersion}, and a caller may reasonably want to know that rather than infer
   * it from a null.
   */
  public boolean hasPublishedCurriculum(UUID domainId) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT count(*) FROM core.curriculum_version
         WHERE domain_id = ? AND status = 'PUBLISHED'
        """, Integer.class, domainId);
    return count != null && count > 0;
  }
}
