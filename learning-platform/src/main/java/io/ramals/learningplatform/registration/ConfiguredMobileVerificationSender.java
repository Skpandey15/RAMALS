package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The SMS sender selected by configuration.
 *
 * <p><strong>DEV and CI use a fake, and production cannot.</strong> Qualifying this capability must
 * not require a paid gateway, so {@code fake} is the default and it is a real implementation of the
 * port rather than a test stub — the ordering, the failure handling and the abuse ceilings around it
 * are exercised exactly as they would be in production. What must never happen is a production
 * deployment quietly selecting it and reporting delivery for messages nobody sent.
 *
 * <p>That is guarded twice, deliberately. {@link RegistrationConfiguration} refuses to start a
 * production context configured with the fake provider, which is the control that matters because it
 * fails closed before serving a single request. The check repeated here covers the narrower case of a
 * deployment whose environment marker is changed after startup, and costs one comparison per send.
 * A single check placed only at startup would be the kind of control that is correct until someone
 * makes the property refreshable.
 *
 * <p>No real gateway adapter ships in this change. A production deployment must therefore configure a
 * provider this class does not recognise, and will fail closed at startup rather than silently
 * degrade — which is the intended state until an adapter is delivered and qualified against the real
 * provider (§23).
 */
@Component
class ConfiguredMobileVerificationSender implements MobileVerificationSender {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ConfiguredMobileVerificationSender.class);

  private static final String FAKE_PROVIDER = "fake";

  private final RegistrationProperties properties;

  ConfiguredMobileVerificationSender(RegistrationProperties properties) {
    this.properties = properties;
  }

  @Override
  public String send(String mobileE164, String otp) {
    String provider = properties.getSms().getProvider();
    if (!FAKE_PROVIDER.equalsIgnoreCase(provider)) {
      throw new IllegalStateException(
          "No SMS adapter is available for provider '" + provider + "'.");
    }
    if (properties.production()) {
      throw new IllegalStateException("The fake SMS provider is prohibited in production.");
    }
    // The code is deliberately absent from this event. A DEV log that printed it would be the
    // easiest place in the system to harvest live codes, and habits formed in DEV are the ones that
    // get copied into the production adapter. Tests substitute a capturing fake for this bean.
    String reference = "fake-" + UUID.randomUUID();
    BusinessEventLogger.info(LOGGER, "mobile.otp.dispatched",
        "Verification code handed to the configured sender",
        Map.of("provider", FAKE_PROVIDER, "providerMessageRef", reference, "outcome", "SUCCESS"));
    return reference;
  }
}
