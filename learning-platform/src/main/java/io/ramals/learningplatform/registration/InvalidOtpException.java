package io.ramals.learningplatform.registration;

import org.springframework.http.HttpStatus;

/**
 * A submitted code did not match the stored keyed HMAC.
 *
 * <p>Its own type because the attempt increment must survive the rejection: under the default
 * rollback-on-RuntimeException the ceiling would never advance and an attacker would get unlimited
 * guesses. {@code verify} declares {@code noRollbackFor} on this subtype, and Spring picks the most
 * specific rollback rule.
 */
final class InvalidOtpException extends RegistrationException {

  InvalidOtpException() {
    super("MOBILE_OTP_INVALID", HttpStatus.CONFLICT,
        "Submitted verification code did not match the stored keyed HMAC.");
  }
}
