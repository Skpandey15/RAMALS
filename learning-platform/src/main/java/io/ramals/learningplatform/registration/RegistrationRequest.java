package io.ramals.learningplatform.registration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The public professional registration contract.
 *
 * <p>No role field, by construction: the realm role is a server-side constant, and a contract test
 * fails the build if a role-shaped component is ever added. No date of birth: adult status is an
 * attested statement version (M1-ADR-015). Consent versions are echoed and then checked against
 * server-known values, so acceptance binds to a specific document revision.
 */
public record RegistrationRequest(
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 32) String mobileNumber,
    @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String country,
    @Size(max = 120) String city,
    @NotBlank @Size(min = 12, max = 128) String password,
    @NotBlank @Size(min = 12, max = 128) String confirmPassword,
    @NotBlank @Size(max = 64) String termsVersion,
    @NotBlank @Size(max = 64) String privacyVersion,
    @NotBlank @Size(max = 64) String adultStatementVersion,
    @AssertTrue boolean termsAccepted,
    @AssertTrue boolean privacyAccepted,
    @AssertTrue boolean adultConfirmed) {

  /**
   * Redacts the whole payload.
   *
   * <p>A record's generated {@code toString} prints every component, so the default would print the
   * password. Everything is withheld rather than just the credentials: the rest is contact PII, and
   * correlation is served by the operation and interaction ids instead.
   */
  @Override
  public String toString() {
    return "RegistrationRequest[REDACTED]";
  }
}
