package io.ramals.learningplatform.learning;

import io.ramals.learningplatform.mastery.MasteryStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class ProgressionRepository {

  private final JdbcTemplate jdbcTemplate;

  public ProgressionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** The latest mastery status per skill for a learner in a curriculum version. */
  public Map<UUID, MasteryStatus> latestStatuses(UUID learnerId, UUID curriculumVersionId) {
    Map<UUID, MasteryStatus> bySkill = new HashMap<>();
    jdbcTemplate.query("""
        SELECT DISTINCT ON (skill_id) skill_id, mastery_status
        FROM ledger.mastery_snapshot
        WHERE learner_id = ? AND curriculum_version_id = ?
        ORDER BY skill_id, aggregate_version DESC
        """, (RowCallbackHandler) result -> bySkill.put(
            result.getObject("skill_id", UUID.class),
            MasteryStatus.valueOf(result.getString("mastery_status"))),
        learnerId, curriculumVersionId);
    return bySkill;
  }

  /** Skills the learner has ever mastered, used to distinguish regression from a plain gap. */
  public Set<UUID> everMasteredSkillIds(UUID learnerId, UUID curriculumVersionId) {
    Set<UUID> ids = new HashSet<>();
    jdbcTemplate.query("""
        SELECT DISTINCT skill_id FROM ledger.mastery_snapshot
        WHERE learner_id = ? AND curriculum_version_id = ? AND mastery_status = 'MASTERED'
        """, (RowCallbackHandler) result -> ids.add(result.getObject("skill_id", UUID.class)),
        learnerId, curriculumVersionId);
    return ids;
  }

  /** Skills whose retention has come due as of the given instant. */
  public Set<UUID> retentionDueSkillIds(UUID learnerId, UUID curriculumVersionId, Instant asOf) {
    Set<UUID> ids = new HashSet<>();
    jdbcTemplate.query("""
        SELECT skill_id FROM core.skill_retention
        WHERE learner_id = ? AND curriculum_version_id = ?
          AND retention_due_at IS NOT NULL AND retention_due_at <= ?
        """, (RowCallbackHandler) result -> ids.add(result.getObject("skill_id", UUID.class)),
        learnerId, curriculumVersionId, OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));
    return ids;
  }
}
