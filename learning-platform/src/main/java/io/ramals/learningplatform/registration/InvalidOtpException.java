package io.ramals.learningplatform.registration;

import org.springframework.http.HttpStatus;

/**
 * A submitted code did not match the stored keyed HMAC.
 *
 * <p><strong>Why this is its own type.</strong> It is the one registration failure whose side effect
 * must survive the failure. {@code MobileVerificationService#verify} increments {@code attempt_count}
 * and then rejects; if that rejection rolled the transaction back, the increment would be undone and
 * the attempt ceiling would never be reached — an attacker would get unlimited guesses at a
 * six-digit code, which is the whole control. The service therefore declares
 * {@code @Transactional(noRollbackFor = InvalidOtpException.class)}, and Spring's rollback rules
 * select the most specific match in the exception hierarchy, so this subtype's no-rollback rule wins
 * over the default rollback-on-RuntimeException that its parent would otherwise attract.
 *
 * <p>It carries {@code MOBILE_OTP_INVALID}, but note that
 * {@link RegistrationException#detail()} deliberately gives it the same learner-facing wording as
 * {@code MOBILE_CHALLENGE_UNAVAILABLE}: a caller must not be able to distinguish a wrong code from an
 * expired, consumed or superseded one.
 */
final class InvalidOtpException extends RegistrationException {

  InvalidOtpException() {
    super("MOBILE_OTP_INVALID", HttpStatus.CONFLICT,
        "Submitted verification code did not match the stored keyed HMAC.");
  }
}
