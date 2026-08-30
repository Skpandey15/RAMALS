package io.ramals.learningplatform.registration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated onboarding continuation.
 *
 * <p><strong>Ownership comes from the token, never from the request.</strong> Every method derives
 * the learner from {@code Authentication#getName()}, which is the validated OIDC {@code sub}. No
 * method accepts a learner id, and there is no path parameter identifying a learner — a caller cannot
 * name a victim, so no amount of authorization logic is required to stop them. The challenge id in
 * {@code verify} is not an exception: the repository query filters on the authenticated learner as
 * well, so a challenge belonging to somebody else reads as absent.
 *
 * <p>These routes sit under {@code /api/v1/me} and require the {@code LEARNER} role, so they run
 * after the platform's existing bearer authentication and subject rate limiting.
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("hasRole('LEARNER')")
class OnboardingController {

  private final OnboardingService onboarding;
  private final MobileVerificationService mobileVerification;

  OnboardingController(OnboardingService onboarding, MobileVerificationService mobileVerification) {
    this.onboarding = onboarding;
    this.mobileVerification = mobileVerification;
  }

  /**
   * Reports the server's view of onboarding.
   *
   * <p>The {@code email_verified} claim is read from the validated JWT rather than from the body.
   * The resource server has already checked the signature, the issuer, the audience and the expiry
   * before this runs, so the claim is Keycloak's assertion — not the browser's. A request body
   * claiming verification would be ignored, because there is no request body.
   */
  @GetMapping("/onboarding")
  OnboardingService.OnboardingResponse onboarding(Authentication authentication) {
    boolean tokenEmailVerified = authentication instanceof JwtAuthenticationToken jwt
        && Boolean.TRUE.equals(jwt.getToken().getClaim("email_verified"));
    return onboarding.current(authentication.getName(), tokenEmailVerified);
  }

  @PostMapping("/mobile/send-otp")
  MobileVerificationService.SendResponse sendOtp(Authentication authentication) {
    return mobileVerification.send(authentication.getName());
  }

  /**
   * Resend is the same operation as send.
   *
   * <p>It exists as a separate route only because the UI distinguishes them. Routing both to one
   * implementation is what keeps the cooldown, the supersede step and the three send ceilings
   * identical for both — a separate resend path would eventually diverge, and the direction it
   * diverges in is always weaker.
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
