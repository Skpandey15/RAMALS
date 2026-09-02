package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * The DEV SMS sink: delivers a verification code to the local Mailpit inbox.
 *
 * <p>Exists because {@code fake} discards the code, which is correct for CI and leaves a person
 * unable to finish onboarding by hand — the OTP page sends, the platform reports success, and no
 * code exists anywhere. The obvious workarounds are worse than the gap. Logging the code turns the
 * DEV log into the easiest place in the system to harvest live codes and forms a habit that gets
 * copied into the real adapter; returning it in the API response puts it on the wire for anyone who
 * can reach the endpoint. Both weaken the thing they are meant to be testing.
 *
 * <p>So the code goes to a mailbox instead of a handset: the same Mailpit the Keycloak verification
 * mail already lands in, at the URL an operator already has open, reachable only from inside the
 * cluster and never exposed by the ingress. It is a sink in the Mailpit sense — a real delivery to a
 * real inbox that happens not to be a phone.
 *
 * <h2>Why this cannot reach production</h2>
 *
 * <p>Three independent things have to be true for this class to send anything, and production
 * satisfies none of them. {@code mailpit} is absent from
 * {@link SmsProviderCatalog#PRODUCTION_CAPABLE}, so {@code RegistrationConfiguration} refuses to
 * start a production context naming it. {@link ConfiguredMobileVerificationSender} repeats that
 * check per send, for an environment marker flipped after startup. And a {@link JavaMailSender}
 * only exists when {@code spring.mail.host} is configured, which only the k3d DEV manifests do — so
 * in production this bean has nothing to send through and says so rather than falling back.
 */
@Component
class MailpitSmsInbox {

  private static final Logger LOGGER = LoggerFactory.getLogger(MailpitSmsInbox.class);

  /**
   * Not a routable domain, and not one anybody owns.
   *
   * <p>Mailpit accepts whatever it is given, but if this ever ran against a relay that did not, the
   * address must fail to route rather than deliver a live verification code to a real mailbox.
   */
  private static final String SINK_DOMAIN = "@sms.ramals.invalid";

  private final ObjectProvider<JavaMailSender> mailSender;
  private final RegistrationProperties properties;

  MailpitSmsInbox(ObjectProvider<JavaMailSender> mailSender, RegistrationProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  /**
   * Delivers the code and returns the provider reference.
   *
   * <p>The reference is what a delivery complaint is correlated by; the code itself is never
   * returned, logged or persisted here, exactly as the port requires of a real gateway.
   */
  String deliver(String mobileE164, String otp) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null) {
      // Fail loudly rather than degrading to the discarding fake: a sink that silently stops
      // delivering is indistinguishable from the problem it was added to solve.
      throw new IllegalStateException(
          "The mailpit SMS sink is selected but no mail sender is configured. Set spring.mail.host "
              + "(SPRING_MAIL_HOST) to the in-cluster Mailpit service.");
    }

    String reference = "mailpit-" + UUID.randomUUID();
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.getSms().getSinkFrom());
    // The recipient is the phone number, so one handset's codes group into one thread in the
    // inbox and a tester can find their own without reading anybody else's.
    message.setTo(mobileE164 + SINK_DOMAIN);
    message.setSubject("RAMALS verification code for " + mobileE164);
    message.setText(otp + "\n\nThis is the RAMALS DEV SMS sink. A real deployment sends this by SMS "
        + "and no component ever stores or logs the code.");
    sender.send(message);

    // The code is deliberately absent from this event, and from every other. What is recorded is
    // that a delivery happened and which one, which is what a complaint needs.
    BusinessEventLogger.info(LOGGER, "mobile.otp.dispatched",
        "Verification code delivered to the DEV SMS sink",
        Map.of("provider", SmsProviderCatalog.MAILPIT, "providerMessageRef", reference,
            "outcome", "SUCCESS"));
    return reference;
  }
}
