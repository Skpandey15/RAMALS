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

  /** Every provider with a working adapter in this build. */
  static final Set<String> SUPPORTED = Set.of(FAKE);

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
