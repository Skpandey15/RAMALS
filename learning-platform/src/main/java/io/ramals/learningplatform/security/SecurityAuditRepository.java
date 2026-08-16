package io.ramals.learningplatform.security;

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

/**
 * Append-only store for authentication and authorization decisions (Master Plan §8).
 *
 * <p>Denials previously existed only in the application log, which has a retention horizon and no
 * immutability guarantee. A security investigation needs a durable record it can reach from an
 * interactionId on a support ticket.
 */
@Repository
public class SecurityAuditRepository {

  private final JdbcTemplate jdbcTemplate;

  public SecurityAuditRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Appends a security audit record.
   *
   * <p>Never pass a token, credential, request body or raw header value in {@code detail}; it is a
   * short safe reason phrase only.
   */
  public void append(
      String eventType, String outcome, String subject, String httpMethod, String route,
      Integer statusCode, String reasonCode, String detail, String interactionId, String traceId) {
    jdbcTemplate.update("""
        INSERT INTO audit.security_audit
          (id, event_type, outcome, subject, http_method, route, status_code, reason_code, detail,
           interaction_id, trace_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, UuidV7.generate(), eventType, outcome, blankToNull(subject), httpMethod,
        route, statusCode, reasonCode, detail, interactionId, blankToNull(traceId));
  }

  public List<SecurityAuditEntry> findByInteractionId(String interactionId) {
    return jdbcTemplate.query("""
        SELECT id, event_type, outcome, subject, http_method, route, status_code, reason_code,
               detail, interaction_id, trace_id, created_at
        FROM audit.security_audit
        WHERE interaction_id = ?
        ORDER BY created_at, id
        """, MAPPER, interactionId);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static final RowMapper<SecurityAuditEntry> MAPPER = (result, row) -> new SecurityAuditEntry(
      result.getObject("id", UUID.class),
      result.getString("event_type"),
      result.getString("outcome"),
      result.getString("subject"),
      result.getString("http_method"),
      result.getString("route"),
      (Integer) result.getObject("status_code"),
      result.getString("reason_code"),
      result.getString("detail"),
      result.getString("interaction_id"),
      result.getString("trace_id"),
      instant(result, "created_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
