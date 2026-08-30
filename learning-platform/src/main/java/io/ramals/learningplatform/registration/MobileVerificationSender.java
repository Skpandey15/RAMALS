package io.ramals.learningplatform.registration;

/**
 * The outbound channel for a verification code.
 *
 * <p>Narrow on purpose: one method, taking the number and the code, returning an opaque provider
 * reference or {@code null}. Implementations must never log, persist or return the code — the
 * reference exists so an operator can correlate a delivery complaint with a gateway record without
 * anyone storing the code itself.
 */
interface MobileVerificationSender {

  /**
   * Sends the code, returning the provider's message reference if it supplies one.
   *
   * @param mobileE164 the normalized destination
   * @param otp the plaintext code; must not outlive the call
   */
  String send(String mobileE164, String otp);
}
