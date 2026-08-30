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

  OnboardingController(OnboardingService onboarding, MobileVerificationService mobileVerification) {
    this.onboarding = onboarding;
    this.mobileVerification = mobileVerification;
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
