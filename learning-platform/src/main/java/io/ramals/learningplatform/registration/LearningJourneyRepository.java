package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.UuidV7;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for the learning journey and the transition it authorises. */
@Repository
class LearningJourneyRepository {

  private final JdbcTemplate jdbc;

  LearningJourneyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Journey> find(UUID learnerId) {
    return jdbc.query("""
        SELECT j.id, j.goal_type, j.target_role, j.learning_intensity, j.weekly_hours, j.status,
               d.code AS primary_domain_code, j.primary_domain_id, j.target_proficiency,
               j.target_date
        FROM identity.learning_journey j
        JOIN core.learning_domain d ON d.id = j.primary_domain_id
        WHERE j.learner_id = ?
        """,
        (row, index) -> new Journey(
            row.getObject("id", UUID.class), row.getString("goal_type"),
            row.getString("target_role"), row.getString("learning_intensity"),
            row.getInt("weekly_hours"), row.getString("status"),
            row.getString("primary_domain_code"),
            row.getObject("primary_domain_id", UUID.class),
            row.getBigDecimal("target_proficiency"),
            Optional.ofNullable(row.getDate("target_date")).map(java.sql.Date::toLocalDate)
                .orElse(null)),
        learnerId).stream().findFirst();
  }

  /**
   * Writes the journey, creating it or replacing it in one statement.
   *
   * <p>The upsert keys on {@code learner_id} rather than {@code id}, which is what makes a
   * resubmission update the learner's one journey instead of minting a second that silently shadows
   * the first. The generated id is therefore only used on insert; a conflict keeps the original, so
   * the journey a learner has kept since onboarding retains its identity across every later edit.
   */
  void save(UUID learnerId, UUID primaryDomainId, LearningJourneyRequest request) {
    jdbc.update("""
        INSERT INTO identity.learning_journey(
            id, learner_id, goal_type, target_role, learning_intensity, weekly_hours, status,
            primary_domain_id, target_proficiency, target_date)
        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
        ON CONFLICT (learner_id) DO UPDATE SET
            goal_type = EXCLUDED.goal_type,
            target_role = EXCLUDED.target_role,
            learning_intensity = EXCLUDED.learning_intensity,
            weekly_hours = EXCLUDED.weekly_hours,
            primary_domain_id = EXCLUDED.primary_domain_id,
            target_proficiency = EXCLUDED.target_proficiency,
            target_date = EXCLUDED.target_date,
            version = identity.learning_journey.version + 1,
            updated_at = CURRENT_TIMESTAMP
        """,
        UuidV7.generate(), learnerId, request.goalType(), request.targetRole().trim(),
        request.learningIntensity(), request.weeklyHours(), primaryDomainId,
        request.targetProficiency(), request.targetDate());
  }

  /**
   * Advances JOURNEY_PENDING to ONBOARDED, and does nothing from any other state.
   *
   * <p>The guard carries the invariant the whole feature exists to protect: a learner at
   * EMAIL_PENDING, MOBILE_PENDING or PROFILE_PENDING cannot be walked to ONBOARDED by submitting a
   * journey, because the UPDATE will not match them. Doc 03 section 10 lists exactly that jump among
   * its forbidden transitions, and this WHERE clause is where the prohibition is enforced -- not in
   * a service branch a later caller might route around.
   */
  int advanceToOnboarded(UUID learnerId) {
    return jdbc.update("""
        UPDATE identity.professional_onboarding
        SET onboarding_state = 'ONBOARDED', version = version + 1, updated_at = CURRENT_TIMESTAMP
        WHERE learner_id = ? AND onboarding_state = 'JOURNEY_PENDING'
        """, learnerId);
  }

  record Journey(UUID id, String goalType, String targetRole, String learningIntensity,
      int weeklyHours, String status, String primaryDomainCode, UUID primaryDomainId,
      BigDecimal targetProficiency, LocalDate targetDate) {
  }
}
