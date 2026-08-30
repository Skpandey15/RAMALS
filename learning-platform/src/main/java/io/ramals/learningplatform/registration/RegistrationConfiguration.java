package io.ramals.learningplatform.registration;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Startup validation for the registration capability.
 *
 * <p><strong>Fail closed, and fail early.</strong> Every check below could instead be made at first
 * use, where it would surface as a learner's registration failing for reasons an operator has to
 * reverse-engineer from a stack trace. Making them startup conditions means a misconfigured
 * deployment never becomes reachable: the container does not come up, the readiness probe never
 * passes, and the rollout stops at the first replica rather than after the first learner.
 *
 * <p>The checks are skipped entirely when the capability is disabled, which is what lets an
 * environment that has not been given a Keycloak admin client or key material run the rest of the
 * platform normally.
 *
 * <p>Messages name the missing setting and never its value. An exception thrown here is logged by the
 * container at ERROR and is frequently the most widely-read line in a failed deployment.
 */
@Configuration
@EnableConfigurationProperties(RegistrationProperties.class)
class RegistrationConfiguration {

  private final RegistrationProperties properties;
  private final OtpHmac otpHmac;

  RegistrationConfiguration(RegistrationProperties properties, OtpHmac otpHmac) {
    this.properties = properties;
    this.otpHmac = otpHmac;
  }

  @PostConstruct
  void validate() {
    if (!properties.isEnabled()) {
      return;
    }
    RegistrationProperties.Keycloak keycloak = properties.getKeycloak();
    requirePresent(keycloak.getBaseUrl(), "ramals.registration.keycloak.base-url");
    requirePresent(keycloak.getRealm(), "ramals.registration.keycloak.realm");
    requirePresent(keycloak.getClientId(), "ramals.registration.keycloak.client-id");
    requirePresent(keycloak.getClientSecret(), "ramals.registration.keycloak.client-secret");

    // The AI plane's workload identity must never be reused for realm administration (M1-ADR-014).
    // It holds a different, non-administrative grant, and pointing registration at it would either
    // fail confusingly or — if somebody later widened that client to make it work — hand the AI
    // plane user-management rights.
    if ("ramals-core-workload".equalsIgnoreCase(keycloak.getClientId())) {
      throw new IllegalStateException(
          "ramals.registration.keycloak.client-id must be a dedicated registration-admin client, "
              + "not the AI workload identity.");
    }

    RegistrationProperties.Consent consent = properties.getConsent();
    requirePresent(consent.getTermsVersion(), "ramals.registration.consent.terms-version");
    requirePresent(consent.getTermsRef(), "ramals.registration.consent.terms-ref");
    requirePresent(consent.getPrivacyVersion(), "ramals.registration.consent.privacy-version");
    requirePresent(consent.getPrivacyRef(), "ramals.registration.consent.privacy-ref");
    requirePresent(consent.getAdultStatementVersion(),
        "ramals.registration.consent.adult-statement-version");

    // Delegated to OtpHmac, which has already parsed and length-checked the ring during construction.
    // Asking it whether the *current* version resolves is the check that matters: a ring that parses
    // but does not contain the version new challenges will be stamped with produces challenges that
    // can never be verified.
    requirePresent(properties.getOtp().getHmacKeyRing(), "ramals.registration.otp.hmac-key-ring");
    if (!otpHmac.hasUsableCurrentKey()) {
      throw new IllegalStateException("ramals.registration.otp.hmac-key-ring does not contain a key "
          + "for the configured ramals.registration.otp.hmac-key-version.");
    }
    if (properties.getOtp().getMaxAttempts() < 1 || properties.getOtp().getMaxAttempts() > 10) {
      // Mirrors the V041 check constraint. Catching it here turns a runtime insert failure during a
      // learner's verification into a startup failure during a deployment.
      throw new IllegalStateException(
          "ramals.registration.otp.max-attempts must be between 1 and 10.");
    }
    if (properties.getOtp().getTtlSeconds() < 60) {
      throw new IllegalStateException(
          "ramals.registration.otp.ttl-seconds must allow at least a minute to enter the code.");
    }

    if (properties.production() && "fake".equalsIgnoreCase(properties.getSms().getProvider())) {
      throw new IllegalStateException(
          "The fake SMS provider is prohibited in production; configure a real sender.");
    }
  }

  private static void requirePresent(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          property + " is required when ramals.registration.enabled is true.");
    }
  }
}
