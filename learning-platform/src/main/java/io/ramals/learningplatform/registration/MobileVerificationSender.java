package io.ramals.learningplatform.registration;

/**
 * The outbound channel for a verification code.
 *
 * <p>Implementations must never log or persist the code. The returned provider reference lets a
 * delivery complaint be correlated with a gateway record without storing the code itself.
 */
interface MobileVerificationSender {

  /** @param otp the plaintext code; must not outlive the call. */
  String send(String mobileE164, String otp);
}
