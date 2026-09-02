package io.ramals.learningplatform.registration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The learning journey a learner submits to clear the JOURNEY_PENDING gate.
 *
 * <p>Carries no onboarding state and no learner id, for the same reasons
 * {@link ProfessionalProfileRequest} carries neither: the transition is decided from stored state,
 * and these are {@code /me} operations bound to the token subject.
 *
 * <p>{@code primaryDomainCode} is a catalog code, not an id. The learner names a domain and the
 * server resolves it against {@code core.learning_domain}, so an unknown or retired domain is a
 * rejection rather than a foreign key the client got to choose. Doc 03 section 8 requires that no
 * domain is ever auto-selected -- the field is mandatory and has no default, so a domain is only
 * ever the one the learner explicitly named.
 *
 * <p>{@code targetProficiency} and {@code targetDate} mirror the existing {@code LearnerGoalRequest}
 * bounds exactly. They are what the journey projects into {@code core.learner_goal}, and a value the
 * legacy goal contract would reject must not become storable simply by arriving through a newer
 * endpoint.
 */
public record LearningJourneyRequest(
    @NotBlank @Pattern(regexp = "ROLE_TRANSITION|DEPTH_IN_CURRENT_ROLE|CERTIFICATION|EXPLORATION")
    String goalType,

    @NotBlank @Size(max = 120) String targetRole,

    @NotBlank @Pattern(regexp = "CASUAL|STEADY|INTENSIVE") String learningIntensity,

    @NotNull @Min(1) @Max(40) Integer weeklyHours,

    @NotBlank
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "must be an uppercase domain code")
    String primaryDomainCode,

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
    @DecimalMax(value = "1", message = "must not exceed 1")
    BigDecimal targetProficiency,

    @FutureOrPresent(message = "must not be in the past") LocalDate targetDate) {

  /**
   * Redacts the payload.
   *
   * <p>Target role and ambitions are professional PII (Doc 03 section 15), and a record's generated
   * {@code toString} would print all of it the first time this lands in an exception message.
   */
  @Override
  public String toString() {
    return "LearningJourneyRequest[REDACTED]";
  }
}
