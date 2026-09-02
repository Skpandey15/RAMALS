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
 * <p>{@code fake} is the default and a real implementation of the port, so ordering, failure
 * handling and the abuse ceilings around it are exercised exactly as in production without a paid
 * gateway. What must never happen is production quietly selecting it and reporting delivery for
 * messages nobody sent, so {@link RegistrationConfiguration} refuses to start a production context
 * configured this way and the check is repeated here for an environment marker changed afterwards.
 *
 * <p>No real gateway adapter ships in this change, so a production deployment must configure a
 * provider this class does not recognise and will fail closed at startup.
 *
 * <p>{@code mailpit} is the DEV sink ({@link MailpitSmsInbox}): the same discarding-versus-real
 * distinction, except the code is delivered to a local inbox so a person can actually finish
 * onboarding. It is validated by the identical production checks below, so selecting it changes
 * where a DEV code goes and nothing about what production will accept.
 */
@Component
class ConfiguredMobileVerificationSender implements MobileVerificationSender {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ConfiguredMobileVerificationSender.class);

  private final RegistrationProperties properties;
  private final MailpitSmsInbox devSmsInbox;

  ConfiguredMobileVerificationSender(RegistrationProperties properties,
      MailpitSmsInbox devSmsInbox) {
    this.properties = properties;
    this.devSmsInbox = devSmsInbox;
  }

  @Override
  public String send(String mobileE164, String otp) {
    String provider = SmsProviderCatalog.normalize(properties.getSms().getProvider());
    if (!SmsProviderCatalog.isSupported(provider)) {
      throw new IllegalStateException(
          "No SMS adapter is available for provider '" + provider + "'.");
    }
    if (properties.production() && !SmsProviderCatalog.isProductionCapable(provider)) {
      throw new IllegalStateException(
          "Provider '" + provider + "' is not production-capable in this build.");
    }
    // Dispatched after both guards, never before: a sink is still a delivery, and it must not be
    // reachable on any path the discarding fake would not also have been reachable on.
    if (SmsProviderCatalog.MAILPIT.equals(provider)) {
      return devSmsInbox.deliver(mobileE164, otp);
    }

    // The code is deliberately absent from this event. A DEV log that printed it would be the
    // easiest place in the system to harvest live codes, and habits formed in DEV are the ones that
    // get copied into the production adapter. Tests substitute a capturing fake for this bean.
    String reference = "fake-" + UUID.randomUUID();
    BusinessEventLogger.info(LOGGER, "mobile.otp.dispatched",
        "Verification code handed to the configured sender",
        Map.of("provider", provider, "providerMessageRef", reference, "outcome", "SUCCESS"));
    return reference;
  }
}
