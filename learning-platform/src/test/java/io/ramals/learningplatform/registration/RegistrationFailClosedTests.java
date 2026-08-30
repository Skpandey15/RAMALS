package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Startup refusals, provider selection, and the redaction of credential-bearing types.
 *
 * <p>Everything here is about a misconfigured deployment never becoming reachable, rather than
 * failing later in front of a learner.
 */
class RegistrationFailClosedTests {

  private static RegistrationProperties valid() {
    RegistrationProperties properties = new RegistrationProperties();
    properties.setEnabled(true);
    properties.getKeycloak().setBaseUrl("http://keycloak:8080");
    properties.getKeycloak().setRealm("ramals");
    properties.getKeycloak().setClientId("ramals-registration-admin");
    properties.getKeycloak().setClientSecret("a-secret-value");
    properties.getConsent().setTermsVersion("terms-v1");
    properties.getConsent().setTermsRef("terms/v1");
    properties.getConsent().setPrivacyVersion("privacy-v1");
    properties.getConsent().setPrivacyRef("privacy/v1");
    properties.getConsent().setAdultStatementVersion("adult-18-v1");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 5);
    properties.getOtp().setHmacKeyRing("v1:" + Base64.getEncoder().encodeToString(key));
    return properties;
  }

  private static void validate(RegistrationProperties properties) {
    new RegistrationConfiguration(properties, new OtpHmac(properties)).validate();
  }

  @Test
  @DisplayName("a fully configured deployment starts")
  void validConfigurationStarts() {
    assertThatCode(() -> validate(valid())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a disabled capability skips every requirement")
  void disabledCapabilitySkipsValidation() {
    RegistrationProperties properties = new RegistrationProperties();
    properties.setEnabled(false);
    // An environment that has not been given an admin client or key material must still run the
    // rest of the platform.
    assertThatCode(() -> validate(properties)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a missing admin secret refuses to start")
  void missingAdminSecretRefusesToStart() {
    RegistrationProperties properties = valid();
    properties.getKeycloak().setClientSecret("  ");
    assertThatThrownBy(() -> validate(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client-secret");
  }

  @Test
  @DisplayName("a missing OTP key ring refuses to start")
  void missingKeyRingRefusesToStart() {
    RegistrationProperties properties = valid();
    properties.getOtp().setHmacKeyRing("");
    assertThatThrownBy(() -> validate(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hmac-key-ring");
  }

  @Test
  @DisplayName("a key ring without the current version refuses to start")
  void keyRingMissingCurrentVersionRefusesToStart() {
    RegistrationProperties properties = valid();
    properties.getOtp().setHmacKeyVersion("v2");
    assertThatThrownBy(() -> validate(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hmac-key-version");
  }

  @Test
  @DisplayName("a missing consent version refuses to start")
  void missingConsentVersionRefusesToStart() {
    RegistrationProperties properties = valid();
    properties.getConsent().setAdultStatementVersion(null);
    assertThatThrownBy(() -> validate(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("adult-statement-version");
  }

  @Test
  @DisplayName("reusing the AI workload identity for realm administration refuses to start")
  void reusingTheWorkloadIdentityRefusesToStart() {
    // M1-ADR-014. Pointing registration at the AI plane's client either fails confusingly or, if
    // somebody widened that client to make it work, hands the AI plane user-management rights.
    RegistrationProperties properties = valid();
    properties.getKeycloak().setClientId("ramals-core-workload");
    assertThatThrownBy(() -> validate(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dedicated registration-admin client");
  }

  @Test
  @DisplayName("the fake SMS provider cannot be selected in production")
  void fakeSmsProviderIsProhibitedInProduction() {
    for (String environment : new String[] {"prod", "production", "PROD", "Production"}) {
      RegistrationProperties properties = valid();
      properties.setEnvironment(environment);
      properties.getSms().setProvider("fake");
      assertThatThrownBy(() -> validate(properties))
          .as("environment %s must refuse the fake provider", environment)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("prohibited in production");
    }
  }

  @Test
  @DisplayName("the fake sender itself refuses to dispatch in production")
  void fakeSenderRefusesAtDispatchTimeToo() {
    // The startup check is the control that matters; this is the second one, covering an environment
    // marker changed after startup. A single check placed only at startup is correct until somebody
    // makes the property refreshable.
    RegistrationProperties properties = valid();
    properties.setEnvironment("prod");
    properties.getSms().setProvider("fake");
    assertThatThrownBy(() -> new ConfiguredMobileVerificationSender(properties)
        .send("+919876543210", "123456"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not production-capable");
  }

  @Test
  @DisplayName("an unrecognised SMS provider fails closed rather than silently degrading")
  void unknownProviderFailsClosed() {
    // No real gateway adapter ships in PR-A, so a production deployment configuring one fails here
    // rather than reporting delivery for messages nobody sent.
    RegistrationProperties properties = valid();
    properties.getSms().setProvider("twilio");
    assertThatThrownBy(() -> new ConfiguredMobileVerificationSender(properties)
        .send("+919876543210", "123456"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No SMS adapter is available");
  }

  @Test
  @DisplayName("an out-of-range attempt ceiling refuses to start")
  void outOfRangeAttemptCeilingRefusesToStart() {
    // Mirrors the V041 check constraint, so the failure lands during deployment rather than during
    // a learner's verification.
    RegistrationProperties properties = valid();
    properties.getOtp().setMaxAttempts(11);
    assertThatThrownBy(() -> validate(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max-attempts");
  }

  // -------------------------------------------------------------------------------------------
  // Redaction
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the registration request never renders the password")
  void registrationRequestIsRedacted() {
    // A record's generated toString prints every component. Without the override this is one
    // log.debug("{}", request) away from writing a live credential into the log pipeline.
    RegistrationRequest request = new RegistrationRequest("Asha", "Iyer", "asha@example.com",
        "9876543210", "IN", "Pune", "correct horse battery", "correct horse battery",
        "terms-v1", "privacy-v1", "adult-18-v1", true, true, true);
    assertThat(request.toString())
        .isEqualTo("RegistrationRequest[REDACTED]")
        .doesNotContain("correct horse battery", "asha@example.com", "9876543210", "Asha");
  }

  @Test
  @DisplayName("the OTP submission never renders the code")
  void verifyRequestIsRedacted() {
    var request = new OnboardingController.VerifyOtpRequest(
        java.util.UUID.randomUUID(), "123456");
    assertThat(request.toString()).isEqualTo("VerifyOtpRequest[REDACTED]").doesNotContain("123456");
  }

  @Test
  @DisplayName("the admin secret and the key ring never render")
  void configurationPropertiesAreRedacted() {
    // These objects reach actuator and diagnostic output.
    RegistrationProperties properties = valid();
    assertThat(properties.getKeycloak().toString())
        .contains("clientSecret=REDACTED")
        .doesNotContain("a-secret-value");
    assertThat(properties.getOtp().toString())
        .contains("hmacKeyRing=REDACTED")
        .doesNotContain(properties.getOtp().getHmacKeyRing());
  }

  // -------------------------------------------------------------------------------------------
  // Normalization
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("national and international forms normalize to one E.164 value")
  void normalizationCollapsesEquivalentForms() {
    // The verified-mobile uniqueness index is only as good as this: if these produced different
    // strings, the constraint would happily store both and the guarantee would be silently void.
    PhoneNormalizer normalizer = new PhoneNormalizer();
    assertThat(normalizer.normalize("9876543210", "IN")).isEqualTo("+919876543210");
    assertThat(normalizer.normalize("09876543210", "IN")).isEqualTo("+919876543210");
    assertThat(normalizer.normalize("+91 98765 43210", "IN")).isEqualTo("+919876543210");
    assertThat(normalizer.normalize("098765-43210", "in")).isEqualTo("+919876543210");
  }

  @Test
  @DisplayName("an invalid number is refused without echoing the submitted value")
  void invalidNumbersAreRefusedWithoutEchoingThem() {
    PhoneNormalizer normalizer = new PhoneNormalizer();
    for (String candidate : new String[] {"12345", "not-a-number", "+9999999999999999"}) {
      assertThatThrownBy(() -> normalizer.normalize(candidate, "IN"))
          .isInstanceOf(RegistrationException.class)
          .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(candidate));
    }
  }
}
