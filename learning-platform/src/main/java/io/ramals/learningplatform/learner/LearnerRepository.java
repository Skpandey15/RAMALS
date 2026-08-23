package io.ramals.learningplatform.learner;

import io.ramals.learningplatform.observability.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LearnerRepository {

  private final JdbcTemplate jdbcTemplate;

  public LearnerRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Idempotently provisions the learner for the given Keycloak subject and returns it. Safe under
   * concurrent first-contact requests: the insert is a no-op on conflict and the row is then read
   * back regardless of which caller created it.
   */
  public Learner provisionForSubject(String subject) {
    jdbcTemplate.update(
        "INSERT INTO core.learner (id, subject) VALUES (?, ?) ON CONFLICT (subject) DO NOTHING",
        UuidV7.generate(), subject);
    return findBySubject(subject).orElseThrow(
        () -> new IllegalStateException("Learner provisioning did not persist a row."));
  }

  public Optional<Learner> findBySubject(String subject) {
    return jdbcTemplate.query(
        "SELECT id, subject, status, created_at, updated_at FROM core.learner WHERE subject = ?",
        LEARNER_MAPPER, subject).stream().findFirst();
  }

  /**
   * Resolves the active learner's opaque subject from its id.
   *
   * <p>Restricted to ACTIVE learners on purpose. A deactivated learner must not have work resumed
   * on their behalf by a background workflow that started before they were deactivated.
   */
  public Optional<String> findActiveSubjectById(UUID learnerId) {
    return jdbcTemplate.query(
        "SELECT subject FROM core.learner WHERE id = ? AND status = 'ACTIVE'",
        (result, row) -> result.getString("subject"), learnerId).stream().findFirst();
  }

  public Optional<UUID> findActiveDomainId(String domainCode) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(
          "SELECT id FROM core.learning_domain WHERE code = ? AND status = 'ACTIVE'",
          (result, row) -> result.getObject("id", UUID.class), domainCode));
    } catch (EmptyResultDataAccessException notFound) {
      return Optional.empty();
    }
  }

  public Optional<LearnerGoal> findGoal(UUID learnerId) {
    return jdbcTemplate.query("""
        SELECT g.learner_id, d.code AS target_domain_code, g.target_proficiency,
               g.target_date, g.created_at, g.updated_at
        FROM core.learner_goal g
        JOIN core.learning_domain d ON d.id = g.target_domain_id
        WHERE g.learner_id = ?
        """, GOAL_MAPPER, learnerId).stream().findFirst();
  }

  /**
   * Inserts or replaces the learner's single goal. The write is idempotent: repeating the same
   * request leaves the same durable state.
   */
  public LearnerGoal upsertGoal(
      UUID learnerId, UUID targetDomainId, java.math.BigDecimal targetProficiency, LocalDate targetDate) {
    jdbcTemplate.update("""
        INSERT INTO core.learner_goal (learner_id, target_domain_id, target_proficiency, target_date)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (learner_id) DO UPDATE SET
          target_domain_id = EXCLUDED.target_domain_id,
          target_proficiency = EXCLUDED.target_proficiency,
          target_date = EXCLUDED.target_date
        """, learnerId, targetDomainId, targetProficiency, targetDate);
    return findGoal(learnerId).orElseThrow(
        () -> new IllegalStateException("Goal upsert did not persist a row."));
  }

  private static final RowMapper<Learner> LEARNER_MAPPER = (result, row) -> new Learner(
      result.getObject("id", UUID.class),
      result.getString("subject"),
      result.getString("status"),
      instant(result, "created_at"),
      instant(result, "updated_at"));

  private static final RowMapper<LearnerGoal> GOAL_MAPPER = (result, row) -> new LearnerGoal(
      result.getObject("learner_id", UUID.class),
      result.getString("target_domain_code"),
      result.getBigDecimal("target_proficiency"),
      result.getObject("target_date", LocalDate.class),
      instant(result, "created_at"),
      instant(result, "updated_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
