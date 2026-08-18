package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.observability.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AdminActivityRepository {

  private final JdbcTemplate jdbcTemplate;

  public AdminActivityRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Appends an audit record using the current JDBC transaction when one is present. */
  public void append(
      String actorSubject, String action, String targetType, UUID targetId, String outcome,
      String detail, String interactionId, String traceId) {
    appendRow(actorSubject, action, targetType, targetId, outcome, detail, interactionId, traceId);
  }

  /**
   * Appends using the caller's current transaction. This named path is used when audit and the
   * domain write must commit or roll back together; JdbcTemplate participates in Spring's bound
   * transaction without changing the legacy rejection-audit semantics above.
   */
  public void appendWithinTransaction(
      String actorSubject, String action, String targetType, UUID targetId, String outcome,
      String detail, String interactionId, String traceId) {
    appendRow(actorSubject, action, targetType, targetId, outcome, detail, interactionId, traceId);
  }

  private void appendRow(
      String actorSubject, String action, String targetType, UUID targetId, String outcome,
      String detail, String interactionId, String traceId) {
    jdbcTemplate.update("""
        INSERT INTO audit.admin_activity
          (id, actor_subject, action, target_type, target_id, outcome, detail, interaction_id,
           trace_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, UuidV7.generate(), actorSubject, action, targetType, targetId, outcome, detail,
        interactionId, traceId == null || traceId.isBlank() ? null : traceId);
  }

  public List<AdminActivity> findByInteractionId(String interactionId) {
    return jdbcTemplate.query("""
        SELECT id, actor_subject, action, target_type, target_id, outcome, detail, interaction_id,
               trace_id, created_at
        FROM audit.admin_activity
        WHERE interaction_id = ?
        ORDER BY created_at, id
        """, MAPPER, interactionId);
  }

  private static final RowMapper<AdminActivity> MAPPER = (result, row) -> new AdminActivity(
      result.getObject("id", UUID.class),
      result.getString("actor_subject"),
      result.getString("action"),
      result.getString("target_type"),
      result.getObject("target_id", UUID.class),
      result.getString("outcome"),
      result.getString("detail"),
      result.getString("interaction_id"),
      result.getString("trace_id"),
      instant(result, "created_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
