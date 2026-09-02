package io.ramals.learningplatform.registration;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The resend-verification contract: an email address and nothing else.
 *
 * <p>No operation id, no idempotency key, no token. A learner who needs this route has lost the
 * mail that would have carried any of them, so requiring one would exclude exactly the people it
 * exists to serve.
 *
 * <p>The bounds match {@link RegistrationRequest#email()}. An address that could not have been
 * registered is rejected by validation rather than turned into a provider lookup.
 */
public record ResendVerificationRequest(@NotBlank @Email @Size(max = 320) String email) {

  /**
   * Withholds the address.
   *
   * <p>A record's generated {@code toString} prints its components, and this one's single component
   * is the personal datum the route exists to keep out of logs.
   */
  @Override
  public String toString() {
    return "ResendVerificationRequest[email=REDACTED]";
  }
}
