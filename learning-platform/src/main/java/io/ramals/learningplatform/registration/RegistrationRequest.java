package io.ramals.learningplatform.registration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The public professional registration contract.
 *
 * <p><strong>There is deliberately no role field, and there never may be.</strong> The realm role is
 * a server-side constant applied by {@link KeycloakRegistrationAdminClient}; the shape of this record
 * is what makes "the public API cannot request a role" true by construction rather than by a
 * validation rule someone could later relax. {@code RegistrationSecurityContractTests} asserts the
 * absence of any role-shaped component so that adding one is a failing build, not a review comment.
 *
 * <p><strong>There is deliberately no date of birth.</strong> Adult status is carried as an attested
 * statement version, not a birth date, so the professional product can prove the attestation without
 * holding a date it has no present use for (M1-ADR-015, and the segment-architecture rule against
 * collecting for hypothetical future segments).
 *
 * <p><strong>Consent versions are echoed, not trusted.</strong> The three version fields are compared
 * against server-known values in {@code RegistrationService}; a request naming a version this
 * deployment did not issue is rejected. They exist so the learner's acceptance is bound to a specific
 * document revision, which a boolean alone cannot express.
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
   * <p>A record's generated {@code toString} prints every component, so the compiler-supplied version
   * of this one prints the password and its confirmation in full. That is one careless
   * {@code log.debug("request={}", request)} — or one framework that renders arguments into a binding
   * or serialization error — away from writing a live credential into the log pipeline, where it is
   * durable, replicated and out of reach. §8 of the delivery contract forbids the password reaching
   * logs at all, and the only version of that rule which survives future edits by people who have not
   * read the rule is one the type enforces itself.
   *
   * <p>Everything is withheld rather than only the two credential fields: the remainder is contact
   * PII, which §22 keeps out of logs and traces for a different reason but with the same outcome. A
   * caller who needs to correlate a specific registration has the operation id and the interaction id,
   * neither of which identifies a person.
   */
  @Override
  public String toString() {
    return "RegistrationRequest[REDACTED]";
  }
}
