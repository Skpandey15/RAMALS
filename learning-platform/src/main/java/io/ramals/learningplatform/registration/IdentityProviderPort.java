package io.ramals.learningplatform.registration;

/**
 * The identity-provider operations registration needs, and no others.
 *
 * <p>Deliberately not a realm-administration abstraction: a port that cannot express "assign an
 * arbitrary role" is one through which an arbitrary role cannot be assigned.
 */
public interface IdentityProviderPort {

  /**
   * Creates the learner identity, or reconciles one that already exists.
   *
   * <p>Three outcomes, not two: created, already-existed, and ambiguous — the provider may have
   * created the user and then failed to say so. Callers must not write contact data for an identity
   * they did not create; see {@code RegistrationService}.
   */
  Identity createLearner(String operationId, RegistrationRequest request);

  /** Returns the trusted email-verification state held by the provider, which is authoritative. */
  boolean emailVerified(String subject);

  /** Asks the provider to send its own verification mail. RAMALS never sends it. */
  void sendVerificationEmail(String subject);

  /**
   * @param subject the OIDC {@code sub} (ADR 0001)
   * @param emailVerified provider-held state, never a browser-supplied boolean
   * @param createdByThisOperation whether this operation created it, or merely found it
   */
  record Identity(String subject, boolean emailVerified, boolean createdByThisOperation) {
  }
}
