package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SMS provider validation as a startup condition, exercised through the application context.
 *
 * <p>Calling {@code sender.send(...)} directly would only show that the sender refuses. What matters
 * is that a production deployment configured for a provider this build has no adapter for never
 * becomes reachable at all: readiness never passes, and the rollout stops at the first replica
 * instead of failing one learner at a time when the first OTP is requested.
 *
 * <p>So these drive {@link ApplicationContextRunner}, which performs real property binding and real
 * bean initialisation, and assert on whether the context starts.
 */
class RegistrationStartupConfigurationTests {

  /** 32 bytes of test key material, Base64. Not a credential; only shape matters here. */
  private static final String KEY_RING =
      "v1:BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=";

  /**
   * Only the collaborator the real configuration needs. {@link RegistrationConfiguration} itself is
   * registered as a configuration class so its {@code @EnableConfigurationProperties} runs and the
   * properties are bound from the environment, exactly as at startup.
   */
  @Configuration(proxyBeanMethods = false)
  static class Wiring {

    @Bean
    OtpHmac otpHmac(RegistrationProperties properties) {
      return new OtpHmac(properties);
    }
  }

  private ApplicationContextRunner contextWith(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(RegistrationConfiguration.class, Wiring.class)
        .withPropertyValues(baseline())
        .withPropertyValues(properties);
  }

  private static String[] baseline() {
    return new String[] {
        "ramals.registration.enabled=true",
        "ramals.registration.keycloak.base-url=http://keycloak:8080",
        "ramals.registration.keycloak.realm=ramals",
        "ramals.registration.keycloak.client-id=ramals-registration-admin",
        "ramals.registration.keycloak.client-secret=test-secret",
        "ramals.registration.keycloak.verification-client-id=ramals-web-ui",
        "ramals.registration.keycloak.verification-redirect-uri=http://localhost:8080/",
        "ramals.registration.consent.terms-version=terms-v1",
        "ramals.registration.consent.terms-ref=terms/v1",
        "ramals.registration.consent.privacy-version=privacy-v1",
        "ramals.registration.consent.privacy-ref=privacy/v1",
        "ramals.registration.consent.adult-statement-version=adult-18-v1",
        "ramals.registration.otp.hmac-key-ring=" + KEY_RING,
    };
  }

  @Test
  @DisplayName("a DEV deployment on the fake provider starts")
  void devWithFakeProviderStarts() {
    contextWith("ramals.registration.environment=dev", "ramals.registration.sms.provider=fake")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("production refuses to start on the fake provider")
  void productionRefusesTheFakeProvider() {
    contextWith("ramals.registration.environment=prod", "ramals.registration.sms.provider=fake")
        .run(context -> assertThat(context).getFailure()
            .hasRootCauseMessage(
                "The fake SMS provider is prohibited in production; configure a real sender."));
  }

  @Test
  @DisplayName("production refuses to start on a provider with no adapter in this build")
  void productionRefusesAProviderWithoutAnAdapter() {
    // The gap this closes: `provider=twilio` in production used to start cleanly and then fail for
    // every learner who asked for a code, because nothing validated that an adapter exists.
    for (String provider : new String[] {"twilio", "sns", "messagebird", "tw1lio"}) {
      contextWith("ramals.registration.environment=prod",
          "ramals.registration.sms.provider=" + provider)
          .run(context -> assertThat(context).getFailure()
              .rootCause()
              .hasMessageContaining("no adapter"));
    }
  }

  @Test
  @DisplayName("even DEV refuses a provider with no adapter")
  void devAlsoRefusesAProviderWithoutAnAdapter() {
    contextWith("ramals.registration.environment=dev", "ramals.registration.sms.provider=twilio")
        .run(context -> assertThat(context).getFailure()
            .rootCause()
            .hasMessageContaining("no adapter"));
  }

  @Test
  @DisplayName("no provider string can start production while no production adapter exists")
  void productionCannotStartAtAllInThisBuild() {
    // PR-A ships no production-capable adapter, so this is the invariant, not a gap: every
    // supported provider is DEV-only, and every unsupported one has no adapter.
    assertThat(SmsProviderCatalog.PRODUCTION_CAPABLE).isEmpty();
    for (String provider : SmsProviderCatalog.SUPPORTED) {
      contextWith("ramals.registration.environment=prod",
          "ramals.registration.sms.provider=" + provider)
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Test
  @DisplayName("a disabled capability starts regardless of SMS configuration")
  void disabledCapabilityIgnoresSmsConfiguration() {
    new ApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(RegistrationConfiguration.class, Wiring.class)
        .withPropertyValues("ramals.registration.enabled=false",
            "ramals.registration.environment=prod",
            "ramals.registration.sms.provider=twilio")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("the AI workload identity is refused as the registration admin client")
  void workloadIdentityIsRefused() {
    contextWith("ramals.registration.keycloak.client-id=ramals-core-workload",
        "ramals.registration.sms.provider=fake")
        .run(context -> assertThat(context).getFailure()
            .rootCause()
            .hasMessageContaining("dedicated registration-admin client"));
  }

  @Test
  @DisplayName("a missing admin secret refuses to start")
  void missingAdminSecretRefusesToStart() {
    contextWith("ramals.registration.keycloak.client-secret=",
        "ramals.registration.sms.provider=fake")
        .run(context -> assertThat(context).getFailure()
            .rootCause()
            .hasMessageContaining("client-secret"));
  }

  @Test
  @DisplayName("a key ring without the current version refuses to start")
  void keyRingMissingCurrentVersionRefusesToStart() {
    contextWith("ramals.registration.otp.hmac-key-version=v2",
        "ramals.registration.sms.provider=fake")
        .run(context -> assertThat(context).getFailure()
            .rootCause()
            .hasMessageContaining("hmac-key-version"));
  }
}
