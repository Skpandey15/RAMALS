package io.ramals.learningplatform.registration;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the professional profile and the one onboarding transition it authorises.
 *
 * <p>Kept beside {@code RegistrationRepository} rather than inside it: that class owns registration,
 * contact PII and mobile challenges, and the professional boundary is a different table with a
 * different lifetime.
 */
@Repository
class ProfessionalProfileRepository {

  private final JdbcTemplate jdbc;

  ProfessionalProfileRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Profile> find(UUID learnerId) {
    return jdbc.query("""
        SELECT current_role_title, experience_band, primary_expertise, declared_skill_level, version
        FROM identity.professional_profile
        WHERE learner_id = ?
        """,
        (row, index) -> new Profile(row.getString(1), row.getString(2), row.getString(3),
            row.getString(4), row.getLong(5)),
        learnerId).stream().findFirst();
  }

  /**
   * Writes the profile, creating it or replacing it in one statement.
   *
   * <p>The upsert is what makes a resubmission idempotent in the sense that matters: a learner who
   * double-submits the form, or returns to correct a typo, ends with one profile row rather than a
   * second one and a unique-violation stack trace. {@code version} increments on update so a later
   * concurrency control has something to read; the row identity never changes.
   */
  void save(UUID learnerId, ProfessionalProfileRequest request) {
    jdbc.update("""
        INSERT INTO identity.professional_profile(
            learner_id, current_role_title, experience_band, primary_expertise, declared_skill_level)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (learner_id) DO UPDATE SET
            current_role_title = EXCLUDED.current_role_title,
            experience_band = EXCLUDED.experience_band,
            primary_expertise = EXCLUDED.primary_expertise,
            declared_skill_level = EXCLUDED.declared_skill_level,
            version = identity.professional_profile.version + 1,
            updated_at = CURRENT_TIMESTAMP
        """,
        learnerId, request.currentRole().trim(), request.experienceBand(),
        request.primaryExpertise().trim(), blankToNull(request.declaredSkillLevel()));
  }

  /**
   * Advances PROFILE_PENDING to JOURNEY_PENDING, and does nothing from any other state.
   *
   * <p>The guard is the invariant, not a convenience. {@code WHERE onboarding_state =
   * 'PROFILE_PENDING'} means a learner who is still EMAIL_PENDING or MOBILE_PENDING cannot be walked
   * forward by submitting a profile, and an already-ONBOARDED learner cannot be walked backwards by
   * resubmitting one. It is the same shape as the transitions in {@code RegistrationRepository}, for
   * the same reason: a state machine enforced in a WHERE clause cannot be bypassed by a caller who
   * reaches the service in an unexpected order.
   *
   * <p>Returns the number of rows moved, so the caller can tell an advance from a no-op without
   * re-reading -- which is what keeps the audit and the business event honest about what happened.
   */
  int advanceToJourneyPending(UUID learnerId) {
    return jdbc.update("""
        UPDATE identity.professional_onboarding
        SET onboarding_state = 'JOURNEY_PENDING', version = version + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE learner_id = ? AND onboarding_state = 'PROFILE_PENDING'
        """, learnerId);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  record Profile(String currentRole, String experienceBand, String primaryExpertise,
      String declaredSkillLevel, long version) {
  }
}
