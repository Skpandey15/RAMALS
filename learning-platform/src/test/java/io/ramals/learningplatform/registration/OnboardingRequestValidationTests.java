package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An incomplete onboarding step is rejected before it reaches a service.
 *
 * <p>{@code @Valid} on the controller already enforces this, so these tests assert something that
 * holds today rather than fixing something broken. That is the point: the constraints are the only
 * thing standing between a half-filled form and a stored profile, and nothing failed if one was
 * loosened. A learner cannot advance a gate with a blank role or a two-hundred-hour week, and now a
 * diff that would let them does not merge.
 *
 * <p>Validated through the real Jakarta validator rather than by asserting the presence of
 * annotations: a constraint that is present but never applied looks identical to one that works.
 */
class OnboardingRequestValidationTests {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void openValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  private static ProfessionalProfileRequest profile(
      String role, String band, String expertise, String level) {
    return new ProfessionalProfileRequest(role, band, expertise, level);
  }

  private static LearningJourneyRequest journey(
      String goalType, String role, String intensity, Integer hours, String domain,
      String proficiency, LocalDate date) {
    return new LearningJourneyRequest(goalType, role, intensity, hours, domain,
        proficiency == null ? null : new BigDecimal(proficiency), date);
  }

  // -------------------------------------------------------------------------------------------
  // Professional profile
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a complete professional profile passes validation")
  void completeProfileIsValid() {
    assertThat(validator.validate(
        profile("Staff Engineer", "FIVE_TO_TEN_YEARS", "Distributed systems", "ADVANCED")))
        .isEmpty();
  }

  @Test
  @DisplayName("an unanswered self-rating is valid; it is the one optional field")
  void absentSkillLevelIsValid() {
    assertThat(validator.validate(
        profile("Staff Engineer", "FIVE_TO_TEN_YEARS", "Distributed systems", null))).isEmpty();
  }

  @Test
  @DisplayName("a profile missing any required field cannot advance the gate")
  void incompleteProfileIsRejected() {
    // Blank, not merely null: " " satisfies @NotNull and is exactly what an accidentally submitted
    // form sends.
    assertThat(validator.validate(
        profile("  ", "FIVE_TO_TEN_YEARS", "Distributed systems", null))).isNotEmpty();
    assertThat(validator.validate(
        profile("Staff Engineer", "FIVE_TO_TEN_YEARS", "", null))).isNotEmpty();
    assertThat(validator.validate(
        profile("Staff Engineer", null, "Distributed systems", null))).isNotEmpty();
  }

  @Test
  @DisplayName("a profile naming a band or level the catalog does not define is rejected")
  void unknownProfileVocabularyIsRejected() {
    assertThat(validator.validate(
        profile("Staff Engineer", "TWENTY_YEARS", "Distributed systems", null))).isNotEmpty();
    assertThat(validator.validate(
        profile("Staff Engineer", "FIVE_TO_TEN_YEARS", "Distributed systems", "GURU")))
        .isNotEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Learning journey
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a complete learning journey passes validation")
  void completeJourneyIsValid() {
    assertThat(validator.validate(journey("ROLE_TRANSITION", "Principal Engineer", "STEADY", 8,
        "KAFKA", "0.800", LocalDate.now().plusMonths(6)))).isEmpty();
  }

  @Test
  @DisplayName("an absent target date is valid; it is the one optional field")
  void absentTargetDateIsValid() {
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "KAFKA",
        "0.500", null))).isEmpty();
  }

  @Test
  @DisplayName("a journey missing any required field cannot complete onboarding")
  void incompleteJourneyIsRejected() {
    assertThat(validator.validate(journey(null, "Engineer", "CASUAL", 2, "KAFKA", "0.5", null)))
        .isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", " ", "CASUAL", 2, "KAFKA", "0.5", null)))
        .isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", null, 2, "KAFKA", "0.5", null)))
        .isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", null, "KAFKA",
        "0.5", null))).isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "", "0.5", null)))
        .isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "KAFKA", null,
        null))).isNotEmpty();
  }

  @Test
  @DisplayName("weekly hours outside the supported range are rejected")
  void weeklyHoursAreBounded() {
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 0, "KAFKA", "0.5",
        null))).isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 41, "KAFKA", "0.5",
        null))).isNotEmpty();
    // The bounds themselves are inclusive and must stay usable.
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 1, "KAFKA", "0.5",
        null))).isEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 40, "KAFKA", "0.5",
        null))).isEmpty();
  }

  @Test
  @DisplayName("target proficiency keeps the legacy goal contract's (0,1] bound")
  void proficiencyMatchesTheLegacyGoalBound() {
    // The same bound LearnerGoalRequest enforces. A value the legacy PUT /me/goal would reject must
    // not become storable simply by arriving through the journey endpoint instead.
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "KAFKA", "0",
        null))).isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "KAFKA", "1.001",
        null))).isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "KAFKA", "1",
        null))).isEmpty();
  }

  @Test
  @DisplayName("a target date in the past is rejected")
  void pastTargetDateIsRejected() {
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "KAFKA", "0.5",
        LocalDate.now().minusDays(1)))).isNotEmpty();
  }

  @Test
  @DisplayName("a domain code that is not a catalog code shape is rejected")
  void malformedDomainCodeIsRejected() {
    // Shape only; existence is the server's business. Both matter: this stops a lowercase or
    // punctuated string reaching the lookup at all.
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "kafka", "0.5",
        null))).isNotEmpty();
    assertThat(validator.validate(journey("EXPLORATION", "Engineer", "CASUAL", 2, "KAF-KA", "0.5",
        null))).isNotEmpty();
  }

  @Test
  @DisplayName("neither request exposes an onboarding-state component")
  void requestsCarryNoOnboardingState() {
    // The record components are the whole contract. If a state field is ever added, the client
    // becomes able to name its own position in the machine, which Doc 03 section 11 forbids.
    for (Class<?> type : new Class<?>[] {
        ProfessionalProfileRequest.class, LearningJourneyRequest.class}) {
      assertThat(type.getRecordComponents())
          .as("%s must not accept onboarding state or a learner id", type.getSimpleName())
          .noneMatch(component -> {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            return name.contains("state") || name.contains("onboard") || name.contains("learnerid");
          });
    }
  }
}
