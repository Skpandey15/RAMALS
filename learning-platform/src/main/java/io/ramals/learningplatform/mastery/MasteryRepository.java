package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.observability.UuidV7;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MasteryRepository {

  private final JdbcTemplate jdbcTemplate;

  public MasteryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<SkillMasteryConfig> findSkillConfig(UUID skillId, UUID curriculumVersionId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          SELECT sv.mastery_threshold, sv.confidence_threshold, sv.required_evidence_count,
                 sv.required_difficulty_bands,
                 (SELECT count(*) FROM core.learning_objective lo
                  WHERE lo.skill_version_id = sv.id AND lo.required) AS required_objectives
          FROM core.skill_version sv
          WHERE sv.skill_id = ? AND sv.curriculum_version_id = ?
          """, (result, row) -> new SkillMasteryConfig(
              result.getBigDecimal("mastery_threshold"),
              result.getBigDecimal("confidence_threshold"),
              result.getInt("required_evidence_count"),
              result.getInt("required_objectives"),
              strings(result.getArray("required_difficulty_bands"))),
          skillId, curriculumVersionId));
    } catch (EmptyResultDataAccessException notConfigured) {
      return Optional.empty();
    }
  }

  private static List<String> strings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    return List.of((String[]) array.getArray());
  }

  /** Ensures the coordination row exists so it can be row-locked for recomputation. */
  public void ensureAggregate(UUID learnerId, UUID skillId, UUID curriculumVersionId) {
    jdbcTemplate.update("""
        INSERT INTO core.learner_skill_aggregate (learner_id, skill_id, curriculum_version_id)
        VALUES (?, ?, ?)
        ON CONFLICT (learner_id, skill_id, curriculum_version_id) DO NOTHING
        """, learnerId, skillId, curriculumVersionId);
  }

  /** Row-locks the aggregate and returns its current version, serializing concurrent recomputes. */
  public int lockAggregateVersion(UUID learnerId, UUID skillId, UUID curriculumVersionId) {
    return jdbcTemplate.queryForObject("""
        SELECT aggregate_version FROM core.learner_skill_aggregate
        WHERE learner_id = ? AND skill_id = ? AND curriculum_version_id = ?
        FOR UPDATE
        """, Integer.class, learnerId, skillId, curriculumVersionId);
  }

  public void advanceAggregateVersion(
      UUID learnerId, UUID skillId, UUID curriculumVersionId, int newVersion) {
    jdbcTemplate.update("""
        UPDATE core.learner_skill_aggregate SET aggregate_version = ?
        WHERE learner_id = ? AND skill_id = ? AND curriculum_version_id = ?
        """, newVersion, learnerId, skillId, curriculumVersionId);
  }

  /**
   * Appends one immutable snapshot for the given aggregate version. Throws a
   * {@link org.springframework.dao.DuplicateKeyException} if a snapshot already exists for that
   * version, so a version can never carry two canonical snapshots.
   */
  public MasterySnapshot insertSnapshot(MasterySnapshotDraft draft) {
    UUID id = UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO ledger.mastery_snapshot
          (id, learner_id, skill_id, curriculum_version_id, aggregate_version, mastery_score,
           mastery_status, threshold, evidence_confidence, confidence_threshold, evidence_count,
           items_considered, algorithm_version, confidence_algorithm_version, interaction_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, draft.learnerId(), draft.skillId(), draft.curriculumVersionId(),
        draft.aggregateVersion(), draft.masteryScore(), draft.status().name(), draft.threshold(),
        draft.evidenceConfidence(), draft.confidenceThreshold(), draft.evidenceCount(),
        draft.itemsConsidered(), draft.algorithmVersion(), draft.confidenceAlgorithmVersion(),
        draft.interactionId());
    return findById(id).orElseThrow(
        () -> new IllegalStateException("Mastery snapshot did not persist."));
  }

  public Optional<MasterySnapshot> findById(UUID id) {
    return jdbcTemplate.query(SNAPSHOT_SELECT + " WHERE id = ?", SNAPSHOT_MAPPER, id)
        .stream().findFirst();
  }

  public Optional<MasterySnapshot> findLatestSnapshot(
      UUID learnerId, UUID skillId, UUID curriculumVersionId) {
    return jdbcTemplate.query(SNAPSHOT_SELECT + """
         WHERE learner_id = ? AND skill_id = ? AND curriculum_version_id = ?
         ORDER BY aggregate_version DESC
         LIMIT 1
        """, SNAPSHOT_MAPPER, learnerId, skillId, curriculumVersionId).stream().findFirst();
  }

  public List<MasterySnapshot> findSnapshots(
      UUID learnerId, UUID skillId, UUID curriculumVersionId) {
    return jdbcTemplate.query(SNAPSHOT_SELECT + """
         WHERE learner_id = ? AND skill_id = ? AND curriculum_version_id = ?
         ORDER BY aggregate_version
        """, SNAPSHOT_MAPPER, learnerId, skillId, curriculumVersionId);
  }

  private static final String SNAPSHOT_SELECT = """
      SELECT id, learner_id, skill_id, curriculum_version_id, aggregate_version, mastery_score,
             mastery_status, threshold, evidence_confidence, confidence_threshold, evidence_count,
             items_considered, algorithm_version, confidence_algorithm_version, interaction_id,
             calculated_at
      FROM ledger.mastery_snapshot
      """;

  private static final RowMapper<MasterySnapshot> SNAPSHOT_MAPPER = (result, row) -> new MasterySnapshot(
      result.getObject("id", UUID.class),
      result.getObject("learner_id", UUID.class),
      result.getObject("skill_id", UUID.class),
      result.getObject("curriculum_version_id", UUID.class),
      result.getInt("aggregate_version"),
      result.getBigDecimal("mastery_score"),
      MasteryStatus.valueOf(result.getString("mastery_status")),
      result.getBigDecimal("threshold"),
      result.getBigDecimal("evidence_confidence"),
      result.getBigDecimal("confidence_threshold"),
      result.getInt("evidence_count"),
      result.getInt("items_considered"),
      result.getString("algorithm_version"),
      result.getString("confidence_algorithm_version"),
      result.getString("interaction_id"),
      instant(result, "calculated_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
