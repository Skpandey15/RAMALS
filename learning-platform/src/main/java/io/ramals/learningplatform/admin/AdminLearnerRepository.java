package io.ramals.learningplatform.admin;

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

@Repository
public class AdminLearnerRepository {

  private final JdbcTemplate jdbcTemplate;

  public AdminLearnerRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<AdminLearnerSummary> findAll() {
    return jdbcTemplate.query(BASE_SELECT + " ORDER BY l.created_at DESC, l.id", MAPPER);
  }

  public Optional<AdminLearnerSummary> findById(UUID learnerId) {
    return jdbcTemplate.query(BASE_SELECT + " WHERE l.id = ?", MAPPER, learnerId)
        .stream().findFirst();
  }

  public int updateStatus(UUID learnerId, String expectedStatus, String newStatus) {
    return jdbcTemplate.update(
        "UPDATE core.learner SET status = ? WHERE id = ? AND status = ?",
        newStatus, learnerId, expectedStatus);
  }

  public long countAll() {
    Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM core.learner", Long.class);
    return value == null ? 0 : value;
  }

  public long countByStatus(String status) {
    Long value = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM core.learner WHERE status = ?", Long.class, status);
    return value == null ? 0 : value;
  }

  public long countOnboardingState(String state) {
    Long value = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM identity.professional_onboarding WHERE onboarding_state = ?",
        Long.class, state);
    return value == null ? 0 : value;
  }

  private static final String BASE_SELECT = """
      SELECT l.id AS learner_id, l.subject, l.status,
             c.first_name, c.last_name, c.email_normalized, c.mobile_e164,
             c.country_code, c.city, c.email_verified_at, c.mobile_verified_at,
             o.onboarding_state, l.created_at, l.updated_at
      FROM core.learner l
      LEFT JOIN identity.learner_contact c ON c.learner_id = l.id
      LEFT JOIN identity.professional_onboarding o ON o.learner_id = l.id
      """;

  private static final RowMapper<AdminLearnerSummary> MAPPER = (result, row) ->
      new AdminLearnerSummary(
          result.getObject("learner_id", UUID.class),
          result.getString("subject"),
          result.getString("status"),
          result.getString("first_name"),
          result.getString("last_name"),
          result.getString("email_normalized"),
          result.getString("mobile_e164"),
          result.getString("country_code"),
          result.getString("city"),
          result.getObject("email_verified_at") != null,
          result.getObject("mobile_verified_at") != null,
          result.getString("onboarding_state"),
          instant(result, "created_at"),
          instant(result, "updated_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
