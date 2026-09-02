package io.ramals.learningplatform.registration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The professional profile a learner submits to clear the PROFILE_PENDING gate.
 *
 * <p>Carries no onboarding state. The caller describes themselves; whether that description is
 * complete enough to advance is decided by {@link ProfessionalProfileService} against the database,
 * never by anything in this payload. A request that could name its own next state would make the
 * client authoritative over a gate M1-PROF-01 puts in the deterministic core.
 *
 * <p>Nor does it carry a learner id. These are {@code /me} operations bound to the token subject
 * (Doc 03 section 12), so there is no field here for one learner to put another learner's id in.
 *
 * <p>{@code declaredSkillLevel} is optional and non-authoritative (Doc 03 section 7): it informs the
 * first journey and is never read as a measured proficiency. Declining to self-rate is a valid
 * answer, so it is nullable rather than defaulted to a guess.
 */
public record ProfessionalProfileRequest(
    @NotBlank @Size(max = 120) String currentRole,
    @NotBlank @Pattern(regexp = "LESS_THAN_ONE_YEAR|ONE_TO_THREE_YEARS|THREE_TO_FIVE_YEARS"
        + "|FIVE_TO_TEN_YEARS|OVER_TEN_YEARS") String experienceBand,
    @NotBlank @Size(max = 120) String primaryExpertise,
    @Pattern(regexp = "BEGINNER|INTERMEDIATE|ADVANCED|EXPERT") String declaredSkillLevel) {

  /**
   * Redacts the payload.
   *
   * <p>Role, employer-shaped free text and expertise are professional PII. Doc 03 section 15 keeps
   * that out of logs and traces, and a record's generated {@code toString} would otherwise print all
   * of it the first time this lands in an exception message.
   */
  @Override
  public String toString() {
    return "ProfessionalProfileRequest[REDACTED]";
  }
}
