package io.ramals.learningplatform.registration;

import java.util.Optional;

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
   * Resolves the subject of an <em>unverified</em> identity for this email, for a resend.
   *
   * <p>Returns empty both when no such identity exists and when it exists but is already verified.
   * Collapsing those two cases here rather than in the caller is the point of the signature: a port
   * that cannot report which of them occurred cannot be made into an account-enumeration oracle by
   * a later caller that forgets to mask the difference. The resend route's whole security property
   * rests on that indistinguishability, so it is enforced at the boundary that knows the answer.
   *
   * @param email the normalized (trimmed, lower-cased) address
   */
  Optional<String> unverifiedSubjectForEmail(String email);

  /**
   * @param subject the OIDC {@code sub} (ADR 0001)
   * @param emailVerified provider-held state, never a browser-supplied boolean
   * @param createdByThisOperation whether this operation created it, or merely found it
   */
  record Identity(String subject, boolean emailVerified, boolean createdByThisOperation) {
  }
}
