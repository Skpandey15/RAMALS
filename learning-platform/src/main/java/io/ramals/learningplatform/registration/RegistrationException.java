package io.ramals.learningplatform.registration;

import org.springframework.http.HttpStatus;

/**
 * A rejected registration or verification command, carrying a stable machine-readable code.
 *
 * <p>The global handler maps every {@code IllegalArgumentException} onto one response code. That
 * is fine for a package with one failure mode; this one rejects for a dozen distinct reasons, and
 * collapsing them means a client cannot tell a retryable refusal from a permanent one and an
 * operator cannot tell abuse control working from a provider failing.
 *
 * <p>The status travels with the code rather than living in a handler switch, so a new code cannot
 * be added at a throw site and forgotten there, silently degrading to 422.
 *
 * <p>Messages are for operators and are never returned; {@link #detail()} supplies the
 * learner-facing text.
 */
public class RegistrationException extends RuntimeException {

  private final String code;
  private final HttpStatus status;

  public RegistrationException(String code, HttpStatus status, String message) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  /**
   * The learner-facing detail for this code.
   *
   * <p>Deliberately coarser than the code where the caller is unauthenticated and the precise reason
   * would be an oracle: the mobile-conflict wording does not confirm that a number is registered,
   * and the OTP failures do not distinguish wrong from expired from consumed from superseded, which
   * would tell an attacker whether they are racing a live challenge.
   */
  public String detail() {
    return switch (code) {
      case "REGISTRATION_DISABLED" ->
          "Self-registration is not currently available.";
      case "REGISTRATION_IDEMPOTENCY_KEY_INVALID" ->
          "A bounded Idempotency-Key header is required for this operation.";
      case "REGISTRATION_IDEMPOTENCY_KEY_CONFLICT" ->
          "This Idempotency-Key was already used for a different registration request.";
      case "PASSWORD_CONFIRMATION_MISMATCH" ->
          "The password and its confirmation do not match.";
      case "CONSENT_VERSION_UNKNOWN" ->
          "The submitted terms, privacy or age statement version is not one this service issued.";
      case "INVALID_MOBILE_NUMBER" ->
          "The mobile number is not a valid number for the selected country.";
      case "REGISTRATION_RATE_LIMITED" ->
          "Too many registration attempts. Try again later.";
      case "IDENTITY_PROVIDER_UNAVAILABLE" ->
          "Account creation is temporarily unavailable. Try again shortly.";
      case "REGISTRATION_REQUIRED" ->
          "This account has not completed professional registration.";
      case "EMAIL_VERIFICATION_REQUIRED" ->
          "Verify your email address before continuing.";
      case "MOBILE_SEND_RATE_LIMITED" ->
          "Too many verification messages requested. Try again later.";
      case "MOBILE_RESEND_COOLDOWN" ->
          "A verification code was just sent. Wait for the cooldown to elapse before requesting another.";
      case "MOBILE_CHALLENGE_UNAVAILABLE", "MOBILE_OTP_INVALID" ->
          "The verification code is not valid. Request a new code.";
      case "MOBILE_OTP_RATE_LIMITED" ->
          "Too many verification attempts. Try again later.";
      case "MOBILE_ALREADY_REGISTERED" ->
          "This mobile number cannot be used for verification.";
      case "ONBOARDING_INCOMPLETE" ->
          "Complete professional onboarding before using this feature.";
      case "SMS_PROVIDER_UNAVAILABLE" ->
          "Verification messages are temporarily unavailable. Try again shortly.";
      default -> "The request could not be completed.";
    };
  }

  /** The retry hint, in seconds, for the codes that are throttles rather than refusals. */
  public long retryAfterSeconds() {
    return switch (code) {
      case "REGISTRATION_RATE_LIMITED", "MOBILE_SEND_RATE_LIMITED", "MOBILE_OTP_RATE_LIMITED" -> 300L;
      case "MOBILE_RESEND_COOLDOWN" -> 60L;
      default -> 0L;
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Factories. Static rather than a code-per-constructor-call so the code/status pairing exists in
  // exactly one place and cannot drift between throw sites.
  // ---------------------------------------------------------------------------------------------

  static RegistrationException disabled() {
    return new RegistrationException("REGISTRATION_DISABLED", HttpStatus.SERVICE_UNAVAILABLE,
        "Registration is disabled by configuration.");
  }

  static RegistrationException idempotencyKeyInvalid() {
    return new RegistrationException("REGISTRATION_IDEMPOTENCY_KEY_INVALID", HttpStatus.BAD_REQUEST,
        "Idempotency-Key header was absent, blank or beyond the bounded length.");
  }

  static RegistrationException idempotencyKeyConflict() {
    return new RegistrationException("REGISTRATION_IDEMPOTENCY_KEY_CONFLICT", HttpStatus.CONFLICT,
        "Idempotency-Key was replayed with a different request fingerprint.");
  }

  static RegistrationException passwordConfirmationMismatch() {
    return new RegistrationException("PASSWORD_CONFIRMATION_MISMATCH", HttpStatus.BAD_REQUEST,
        "Password confirmation did not match.");
  }

  static RegistrationException consentVersionUnknown() {
    return new RegistrationException("CONSENT_VERSION_UNKNOWN", HttpStatus.UNPROCESSABLE_ENTITY,
        "Submitted consent or age-attestation version is not server-known.");
  }

  static RegistrationException invalidMobileNumber() {
    return new RegistrationException("INVALID_MOBILE_NUMBER", HttpStatus.BAD_REQUEST,
        "Mobile number failed E.164 normalization for the submitted country.");
  }

  static RegistrationException registrationRateLimited(String dimension) {
    return new RegistrationException("REGISTRATION_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
        "Registration abuse ceiling reached for dimension " + dimension + ".");
  }

  static RegistrationException identityProviderUnavailable(String operation, Throwable cause) {
    RegistrationException exception = new RegistrationException("IDENTITY_PROVIDER_UNAVAILABLE",
        HttpStatus.SERVICE_UNAVAILABLE,
        "Identity provider call '" + operation + "' failed and could not be reconciled.");
    exception.initCause(cause);
    return exception;
  }

  static RegistrationException registrationRequired() {
    return new RegistrationException("REGISTRATION_REQUIRED", HttpStatus.CONFLICT,
        "Authenticated subject has no professional registration contact record.");
  }

  static RegistrationException emailVerificationRequired() {
    return new RegistrationException("EMAIL_VERIFICATION_REQUIRED", HttpStatus.CONFLICT,
        "Trusted email verification is required before mobile verification.");
  }

  static RegistrationException mobileSendRateLimited(String dimension) {
    return new RegistrationException("MOBILE_SEND_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
        "Mobile verification send ceiling reached for dimension " + dimension + ".");
  }

  static RegistrationException resendCooldown() {
    return new RegistrationException("MOBILE_RESEND_COOLDOWN", HttpStatus.TOO_MANY_REQUESTS,
        "Resend requested inside the configured cooldown window.");
  }

  static RegistrationException challengeUnavailable(String reason) {
    return new RegistrationException("MOBILE_CHALLENGE_UNAVAILABLE", HttpStatus.CONFLICT,
        "Mobile verification challenge is unusable: " + reason + ".");
  }

  static RegistrationException otpVerifyRateLimited() {
    return new RegistrationException("MOBILE_OTP_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
        "Mobile verification attempt ceiling reached for this subject.");
  }

  static RegistrationException mobileAlreadyRegistered() {
    return new RegistrationException("MOBILE_ALREADY_REGISTERED", HttpStatus.CONFLICT,
        "Mobile number is already verified against a different learner identity.");
  }

  static RegistrationException onboardingIncomplete(String currentState) {
    return new RegistrationException("ONBOARDING_INCOMPLETE", HttpStatus.FORBIDDEN,
        "Professional onboarding is incomplete; current state is " + currentState + ".");
  }

  static RegistrationException smsProviderUnavailable(Throwable cause) {
    RegistrationException exception = new RegistrationException("SMS_PROVIDER_UNAVAILABLE",
        HttpStatus.SERVICE_UNAVAILABLE, "Configured SMS sender could not deliver the challenge.");
    exception.initCause(cause);
    return exception;
  }
}
