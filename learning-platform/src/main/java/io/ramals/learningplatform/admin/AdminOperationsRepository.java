package io.ramals.learningplatform.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminOperationsRepository {

  private final JdbcTemplate jdbcTemplate;

  public AdminOperationsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long countLearners(String status) {
    return scalar("SELECT COUNT(*) FROM core.learner WHERE status = ?", status);
  }

  public long countLearners() {
    return scalar("SELECT COUNT(*) FROM core.learner");
  }

  public long countOnboarded() {
    return scalar("SELECT COUNT(*) FROM identity.professional_onboarding WHERE onboarding_state = 'ONBOARDED'");
  }

  public long countCurricula(String status) {
    return scalar("SELECT COUNT(*) FROM core.curriculum_version WHERE status = ?", status);
  }

  public long countAuthorizationDenials24h() {
    return scalar("""
        SELECT COUNT(*) FROM audit.security_audit
        WHERE outcome = 'DENIED' AND created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
        """);
  }

  public long countAdminActions24h() {
    return scalar("""
        SELECT COUNT(*) FROM audit.admin_activity
        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
        """);
  }

  private long scalar(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }
}
