package io.ramals.learningplatform.registration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated onboarding continuation.
 *
 * <p>Ownership comes from the validated token, never the request: no method accepts a learner id,
 * so a caller cannot name a victim. The challenge id in {@code verify} is no exception - the
 * repository query filters on the authenticated learner, so another learner's challenge reads as
 * absent.
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("hasRole('LEARNER')")
class OnboardingController {

  private final OnboardingService onboarding;
  private final MobileVerificationService mobileVerification;
  private final ProfessionalProfileService professionalProfile;
  private final LearningJourneyService learningJourney;

  OnboardingController(OnboardingService onboarding, MobileVerificationService mobileVerification,
      ProfessionalProfileService professionalProfile, LearningJourneyService learningJourney) {
    this.onboarding = onboarding;
    this.mobileVerification = mobileVerification;
    this.professionalProfile = professionalProfile;
    this.learningJourney = learningJourney;
  }

  /**
   * Reports the server's view of onboarding. The {@code email_verified} claim comes from the JWT the
   * resource server already validated - Keycloak's assertion, not the browser's.
   */
  @GetMapping("/onboarding")
  OnboardingService.OnboardingResponse onboarding(Authentication authentication) {
    boolean tokenEmailVerified = authentication instanceof JwtAuthenticationToken jwt
        && Boolean.TRUE.equals(jwt.getToken().getClaim("email_verified"));
    return onboarding.current(authentication.getName(), tokenEmailVerified);
  }

  /**
   * The caller's professional profile, or 404 before they have completed the step.
   *
   * <p>Deliberately NOT {@code /me/profile}: {@link
   * io.ramals.learningplatform.learner.LearnerController} already serves that path with the
   * operational learner profile. Doc 03 section 12 lists {@code /me/profile} among candidate
   * contracts, but the two are different resources -- M1-ADR-012 keeps operational identity and
   * professional attributes on opposite sides of a boundary -- and collapsing them onto one path
   * would merge exactly what the LLD separates.
   */
  @GetMapping("/professional-profile")
  ResponseEntity<ProfessionalProfileService.ProfessionalProfileResponse> professionalProfile(
      Authentication authentication) {
    return professionalProfile.current(authentication.getName())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Stores the professional profile, advancing onboarding when it completes the step.
   *
   * <p>The response reports the stored profile, not the onboarding state: the client re-reads
   * {@code /me/onboarding} for that, so there is exactly one authority on where a learner stands and
   * no second copy for the two to disagree about.
   */
  @PutMapping("/professional-profile")
  ProfessionalProfileService.ProfessionalProfileResponse saveProfessionalProfile(
      Authentication authentication, @Valid @RequestBody ProfessionalProfileRequest request) {
    return professionalProfile.save(authentication.getName(), request);
  }

  /**
   * The active learning domains the journey step may offer.
   *
   * <p>Served rather than hard-coded in the client, which is what keeps Doc 03 section 8's rule that
   * no domain is auto-selected true by construction: the UI renders whatever the catalog holds, with
   * no preselection, so a domain is only ever the one the learner picked.
   */
  @GetMapping("/learning-domains")
  List<LearningJourneyService.LearningDomainResponse> learningDomains() {
    return learningJourney.availableDomains();
  }

  /** The caller's learning journey, or 404 before they have completed the step. */
  @GetMapping("/learning-journeys")
  ResponseEntity<LearningJourneyService.LearningJourneyResponse> learningJourney(
      Authentication authentication) {
    return learningJourney.current(authentication.getName())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Records the learning journey, projects the primary goal, and completes onboarding.
   *
   * <p>POST rather than PUT to match Doc 03 section 12's {@code POST/GET /me/learning-journeys},
   * though the handler is idempotent: one journey per learner, upserted. The response carries the
   * journey, not the onboarding state -- the client re-reads {@code /me/onboarding} for that, so
   * there is one authority on where a learner stands rather than two that can disagree.
   */
  @PostMapping("/learning-journeys")
  LearningJourneyService.LearningJourneyResponse saveLearningJourney(
      Authentication authentication, @Valid @RequestBody LearningJourneyRequest request) {
    return learningJourney.save(authentication.getName(), request);
  }

  @PostMapping("/mobile/send-otp")
  MobileVerificationService.SendResponse sendOtp(Authentication authentication) {
    return mobileVerification.send(authentication.getName());
  }

  /**
   * Resend routes to send, so the cooldown, the supersede step and the three ceilings stay identical
   * for both; a separate path would eventually diverge, and always in the weaker direction.
   */
  @PostMapping("/mobile/resend-otp")
  MobileVerificationService.SendResponse resendOtp(Authentication authentication) {
    return mobileVerification.send(authentication.getName());
  }

  @PostMapping("/mobile/verify-otp")
  MobileVerificationService.VerifyResponse verifyOtp(Authentication authentication,
      @Valid @RequestBody VerifyOtpRequest request) {
    return mobileVerification.verify(authentication.getName(), request.challengeId(), request.otp());
  }

  /** The code is bounded and shape-checked here; it is matched against the keyed HMAC downstream. */
  record VerifyOtpRequest(@NotNull UUID challengeId, @NotBlank @Size(max = 12) String otp) {

    /** Redacted for the same reason {@link RegistrationRequest#toString()} is. */
    @Override
    public String toString() {
      return "VerifyOtpRequest[REDACTED]";
    }
  }
}
