package io.ramals.learningplatform.registration;

/**
 * The narrow set of identity-provider operations professional registration needs.
 *
 * <p><strong>Deliberately not a realm-administration abstraction.</strong> Four operations, each one
 * a step this package actually performs. The temptation with a Keycloak admin credential is to wrap
 * it in something general — {@code createUser}, {@code updateUser}, {@code assignRole(role)} — and
 * that generality is precisely what turns a least-privilege service account into an ambient one. A
 * port that cannot express "assign an arbitrary role" is a port through which an arbitrary role
 * cannot be assigned, whatever a future caller intends. The realm role is a constant inside the
 * adapter for the same reason.
 *
 * <p>The interface exists so the identity boundary can be substituted in tests without a Keycloak
 * instance. It does not exist to make the provider swappable: {@code M1-ADR-015} makes Keycloak the
 * authority for credentials and for email-verification state, and a second implementation would have
 * to reproduce that authority rather than merely satisfy these signatures.
 */
public interface IdentityProviderPort {

  /**
   * Creates the learner identity, or reconciles one that already exists.
   *
   * <p>The return value's {@link Identity#createdByThisOperation()} flag is the security-relevant
   * part. Creation spans two systems with no shared transaction, so this call has three possible
   * real-world outcomes, not two: created, already-existed, and <em>ambiguous</em> — the provider may
   * have created the user and then failed to tell us. The adapter resolves the ambiguity by looking
   * the identity up and reading back the operation id it stamped on the user, which is the
   * "reconcile using non-secret stable identifiers" rule; the flag reports the answer.
   *
   * <p>Callers must not write contact data for an identity they did not create. See
   * {@code RegistrationService} for why that distinction is load-bearing rather than tidy.
   */
  Identity createLearner(String operationId, RegistrationRequest request);

  /** Returns the trusted email-verification state held by the provider, which is authoritative. */
  boolean emailVerified(String subject);

  /** Asks the provider to send its own verification mail. RAMALS never sends it. */
  void sendVerificationEmail(String subject);

  /**
   * An identity as the provider reports it.
   *
   * @param subject the OIDC {@code sub}, RAMALS's immutable external identity anchor (ADR 0001)
   * @param emailVerified provider-held verification state; never a browser-supplied boolean
   * @param createdByThisOperation whether this registration operation created the identity, as
   *     opposed to finding one that already existed under the same email
   */
  record Identity(String subject, boolean emailVerified, boolean createdByThisOperation) {
  }
}
