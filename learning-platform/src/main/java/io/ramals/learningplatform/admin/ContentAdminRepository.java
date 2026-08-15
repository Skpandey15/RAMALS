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
public class ContentAdminRepository {

  private final JdbcTemplate jdbcTemplate;

  public ContentAdminRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<CurriculumVersionSummary> listCurriculumVersions() {
    return jdbcTemplate.query(SELECT + " ORDER BY d.code, cv.version_code", MAPPER);
  }

  public Optional<CurriculumVersionSummary> findCurriculumVersion(UUID curriculumVersionId) {
    return jdbcTemplate.query(SELECT + " WHERE cv.id = ?", MAPPER, curriculumVersionId)
        .stream().findFirst();
  }

  /**
   * Transitions a DRAFT version to PUBLISHED. The publication trigger validates that the version has
   * skills and required objectives and stamps published_at; a version that is not DRAFT matches no
   * rows. Returns true if this call performed the transition.
   */
  public boolean publishCurriculumVersion(UUID curriculumVersionId) {
    return jdbcTemplate.update("""
        UPDATE core.curriculum_version SET status = 'PUBLISHED'
        WHERE id = ? AND status = 'DRAFT'
        """, curriculumVersionId) == 1;
  }

  /** Transitions a PUBLISHED version to RETIRED; retired versions remain queryable as history. */
  public boolean retireCurriculumVersion(UUID curriculumVersionId) {
    return jdbcTemplate.update("""
        UPDATE core.curriculum_version SET status = 'RETIRED'
        WHERE id = ? AND status = 'PUBLISHED'
        """, curriculumVersionId) == 1;
  }

  private static final String SELECT = """
      SELECT cv.id, d.code AS domain_code, cv.version_code, cv.status, cv.published_at
      FROM core.curriculum_version cv
      JOIN core.learning_domain d ON d.id = cv.domain_id
      """;

  private static final RowMapper<CurriculumVersionSummary> MAPPER = (result, row) ->
      new CurriculumVersionSummary(
          result.getObject("id", UUID.class),
          result.getString("domain_code"),
          result.getString("version_code"),
          result.getString("status"),
          instant(result, "published_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
