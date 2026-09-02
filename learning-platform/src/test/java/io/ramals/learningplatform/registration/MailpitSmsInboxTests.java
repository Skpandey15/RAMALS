package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The DEV SMS sink.
 *
 * <p>The cases that matter are about containment rather than delivery. A sink that leaks the code
 * anywhere other than the mailbox — into a log, a return value, an address that could route
 * outside — is worse than the gap it was added to close, because it looks like it is working.
 */
class MailpitSmsInboxTests {

  private static final String MOBILE = "+919876543210";
  private static final String OTP = "482913";

  private JavaMailSender mailSender;
  private RegistrationProperties properties;
  private MailpitSmsInbox inbox;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    mailSender = mock(JavaMailSender.class);
    ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(mailSender);
    properties = new RegistrationProperties();
    inbox = new MailpitSmsInbox(provider, properties);
  }

  private SimpleMailMessage delivered() {
    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(message.capture());
    return message.getValue();
  }

  @Test
  @DisplayName("the code is delivered to the inbox")
  void deliversTheCode() {
    inbox.deliver(MOBILE, OTP);

    // The whole point: unlike the discarding fake, the code exists somewhere a person can read it.
    assertThat(delivered().getText()).contains(OTP);
  }

  @Test
  @DisplayName("the recipient groups a handset's codes and cannot route outside the cluster")
  void addressesANonRoutableSinkDomain() {
    inbox.deliver(MOBILE, OTP);
    String[] to = delivered().getTo();

    assertThat(to).isNotNull();
    // The number is the local part, so one tester finds their own codes without reading anybody
    // else's; `.invalid` is reserved by RFC 2606 and can never resolve, so a misconfigured relay
    // fails to deliver rather than mailing a live code to a real address.
    assertThat(to[0]).startsWith(MOBILE).endsWith(".invalid");
  }

  @Test
  @DisplayName("the code is never returned to the caller")
  void neverReturnsTheCode() {
    String reference = inbox.deliver(MOBILE, OTP);

    // The port's contract: a reference correlates a delivery complaint with a gateway record, and
    // must not itself be a way to read the code back.
    assertThat(reference).startsWith("mailpit-").doesNotContain(OTP);
  }

  @Test
  @DisplayName("a missing mail sender fails loudly rather than silently discarding")
  @SuppressWarnings("unchecked")
  void failsLoudlyWithoutAMailSender() {
    ObjectProvider<JavaMailSender> empty = mock(ObjectProvider.class);
    when(empty.getIfAvailable()).thenReturn(null);

    // Degrading to the fake's behaviour would reproduce exactly the symptom this exists to fix,
    // while reporting success -- the worst of both.
    assertThatThrownBy(() -> new MailpitSmsInbox(empty, properties).deliver(MOBILE, OTP))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no mail sender is configured");
  }

  @Test
  @DisplayName("the sink posts under the configured from-address")
  void usesTheConfiguredSender() {
    properties.getSms().setSinkFrom("sink@example.invalid");

    inbox.deliver(MOBILE, OTP);

    assertThat(delivered().getFrom()).isEqualTo("sink@example.invalid");
  }
}
