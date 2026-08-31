package io.ramals.learningplatform.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAuditQueryRepository {

  private final JdbcTemplate jdbcTemplate;

  public AdminAuditQueryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<AdminActivityView> recentAdminActivity(int limit) {
    return jdbcTemplate.query("""
        SELECT id, actor_subject, action, target_type, target_id, outcome, detail,
               interaction_id, trace_id, created_at
        FROM audit.admin_activity
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """, (rs, row) -> new AdminActivityView(
            rs.getObject("id", UUID.class), rs.getString("actor_subject"), rs.getString("action"),
            rs.getString("target_type"), rs.getObject("target_id", UUID.class), rs.getString("outcome"),
            rs.getString("detail"), rs.getString("interaction_id"), rs.getString("trace_id"),
            instant(rs.getObject("created_at", OffsetDateTime.class))), limit);
  }

  public List<SecurityAuditView> recentSecurityActivity(int limit) {
    return jdbcTemplate.query("""
        SELECT id, event_type, outcome, subject, http_method, route, status_code,
               reason_code, detail, interaction_id, trace_id, created_at
        FROM audit.security_audit
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """, (rs, row) -> new SecurityAuditView(
            rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("outcome"),
            rs.getString("subject"), rs.getString("http_method"), rs.getString("route"),
            (Integer) rs.getObject("status_code"), rs.getString("reason_code"), rs.getString("detail"),
            rs.getString("interaction_id"), rs.getString("trace_id"),
            instant(rs.getObject("created_at", OffsetDateTime.class))), limit);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  public record AdminActivityView(
      UUID id, String actorSubject, String action, String targetType, UUID targetId,
      String outcome, String detail, String interactionId, String traceId, Instant createdAt) {}

  public record SecurityAuditView(
      UUID id, String eventType, String outcome, String subject, String httpMethod, String route,
      Integer statusCode, String reasonCode, String detail, String interactionId, String traceId,
      Instant createdAt) {}
}
