package io.ramals.learningplatform.learning;

import io.ramals.learningplatform.observability.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class LearningSessionRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public LearningSessionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /** Creates a new ACTIVE session. Throws DuplicateKeyException if one is already open. */
  public LearningSession insertSession(UUID learnerId, UUID curriculumVersionId, String interactionId) {
    UUID id = UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.learning_session
          (id, learner_id, curriculum_version_id, created_interaction_id, last_interaction_id,
           last_command)
        VALUES (?, ?, ?, ?, ?, 'START')
        """, id, learnerId, curriculumVersionId, interactionId, interactionId);
    return findById(id).orElseThrow(
        () -> new IllegalStateException("Learning session did not persist."));
  }

  public Optional<LearningSession> findOpenSession(UUID learnerId, UUID curriculumVersionId) {
    return jdbcTemplate.query(SESSION_SELECT + """
         WHERE s.learner_id = ? AND s.curriculum_version_id = ?
           AND s.status IN ('ACTIVE', 'PAUSED')
        """, sessionMapper(), learnerId, curriculumVersionId).stream().findFirst();
  }

  public Optional<LearningSession> findByIdAndLearner(UUID sessionId, UUID learnerId) {
    return jdbcTemplate.query(SESSION_SELECT + " WHERE s.id = ? AND s.learner_id = ?",
        sessionMapper(), sessionId, learnerId).stream().findFirst();
  }

  public List<LearningSession> findByLearner(UUID learnerId) {
    return jdbcTemplate.query(SESSION_SELECT + " WHERE s.learner_id = ? ORDER BY s.started_at DESC",
        sessionMapper(), learnerId);
  }

  private Optional<LearningSession> findById(UUID sessionId) {
    return jdbcTemplate.query(SESSION_SELECT + " WHERE s.id = ?", sessionMapper(), sessionId)
        .stream().findFirst();
  }

  /**
   * Applies a transition only if the version still matches (optimistic concurrency). Returns true if
   * this call won the race and updated the row.
   */
  public boolean applyTransition(
      UUID sessionId, int expectedVersion, LearningSessionStatus targetStatus, int newVersion,
      LearningSessionCommand command, String interactionId, String checkpointJson) {
    return jdbcTemplate.update("""
        UPDATE core.learning_session SET
          status = ?, version = ?, last_command = ?, last_interaction_id = ?,
          checkpoint_jsonb = COALESCE(?::jsonb, checkpoint_jsonb),
          completed_at = CASE WHEN ? = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE completed_at END
        WHERE id = ? AND version = ?
        """, targetStatus.name(), newVersion, command.name(), interactionId, checkpointJson,
        targetStatus.name(), sessionId, expectedVersion) == 1;
  }

  public void insertTransition(
      UUID sessionId, LearningSessionStatus fromStatus, LearningSessionStatus toStatus,
      String command, int versionAfter, String interactionId, String traceId) {
    jdbcTemplate.update("""
        INSERT INTO core.learning_session_transition
          (id, session_id, from_status, to_status, command, version_after, interaction_id, trace_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, UuidV7.generate(), sessionId, fromStatus == null ? null : fromStatus.name(),
        toStatus.name(), command, versionAfter, interactionId,
        traceId == null || traceId.isBlank() ? null : traceId);
  }

  private static final String SESSION_SELECT = """
      SELECT s.id, s.learner_id, s.curriculum_version_id, d.code AS domain_code,
             cv.version_code, s.status, s.version, s.checkpoint_jsonb AS checkpoint,
             s.started_at, s.updated_at, s.completed_at
      FROM core.learning_session s
      JOIN core.curriculum_version cv ON cv.id = s.curriculum_version_id
      JOIN core.learning_domain d ON d.id = cv.domain_id
      """;

  private RowMapper<LearningSession> sessionMapper() {
    return (result, row) -> new LearningSession(
        result.getObject("id", UUID.class),
        result.getObject("learner_id", UUID.class),
        result.getObject("curriculum_version_id", UUID.class),
        result.getString("domain_code"),
        result.getString("version_code"),
        LearningSessionStatus.valueOf(result.getString("status")),
        result.getInt("version"),
        parseCheckpoint(result.getString("checkpoint")),
        instant(result, "started_at"),
        instant(result, "updated_at"),
        instant(result, "completed_at"));
  }

  private JsonNode parseCheckpoint(String checkpointJson) {
    return checkpointJson == null ? objectMapper.createObjectNode()
        : objectMapper.readTree(checkpointJson);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
