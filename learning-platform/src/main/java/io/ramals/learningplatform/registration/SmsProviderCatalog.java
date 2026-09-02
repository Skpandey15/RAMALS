package io.ramals.learningplatform.registration;

import java.util.Locale;
import java.util.Set;

/**
 * Which SMS providers this build actually has an adapter for, and which of those may run in
 * production.
 *
 * <p>Startup validation previously rejected only {@code fake} in production, so a deployment naming
 * any other string - {@code twilio}, a typo, a provider nobody has written - started successfully
 * and failed later, one learner at a time, when the first OTP was requested. A capability the
 * service does not have should stop a rollout, not a learner.
 *
 * <p>{@link #PRODUCTION_CAPABLE} is deliberately empty. PR-A ships no real gateway adapter, so there
 * is no production-capable provider and production registration cannot start. That is the intended
 * state and the reason real-provider SMS is recorded as BLOCKED rather than merely unverified;
 * emptiness here is the invariant, not an oversight.
 *
 * <p>Adding a provider later is two edits - an adapter, and its name in both sets - and the
 * fail-closed rule needs no weakening to accommodate it.
 */
final class SmsProviderCatalog {

  /** The non-billable adapter used for DEV and CI. Never production-capable. */
  static final String FAKE = "fake";

  /**
   * The DEV sink: delivers the code to the local Mailpit inbox instead of a handset.
   *
   * <p>{@link #FAKE} discards the code deliberately, which is right for CI and wrong for a person
   * trying to finish onboarding by hand — mobile verification simply cannot be completed in DEV.
   * The alternative fixes people reach for are worse than the problem: logging the code makes the
   * DEV log the easiest place in the system to harvest live codes, and a habit formed there is the
   * one that gets copied into the real adapter. This routes the code to a mailbox that already
   * exists, is already the place an operator looks, and is reachable only from inside the cluster.
   *
   * <p>Absent from {@link #PRODUCTION_CAPABLE}, so a production deployment naming it fails at
   * startup on the same check that rejects {@link #FAKE}.
   */
  static final String MAILPIT = "mailpit";

  /** Every provider with a working adapter in this build. */
  static final Set<String> SUPPORTED = Set.of(FAKE, MAILPIT);

  /** The subset that may serve production traffic. Empty until a real adapter is delivered. */
  static final Set<String> PRODUCTION_CAPABLE = Set.of();

  private SmsProviderCatalog() {
  }

  static String normalize(String provider) {
    return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
  }

  static boolean isSupported(String provider) {
    return SUPPORTED.contains(normalize(provider));
  }

  static boolean isProductionCapable(String provider) {
    return PRODUCTION_CAPABLE.contains(normalize(provider));
  }
}
