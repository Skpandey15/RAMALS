package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mobile ownership verification lifecycle.
 *
 * <p>Uses a real {@link OtpHmac} rather than a stub, so the MAC these tests verify against is the one
 * production computes. Substituting it would make every assertion here about the test double.
 */
class MobileVerificationServiceTests {

  private static final String SUBJECT = "subject-1";
  private static final String MOBILE = "+919876543210";
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID CHALLENGE_ID = UUID.fromString("01900000-0000-7000-8000-0000000000b2");

  private RegistrationProperties properties;
  private RegistrationRepository registrations;
  private LearnerRepository learners;
  private OtpHmac otpHmac;
  private RecordingSender sender;
  private AbuseCeiling ceilings;
  private SimpleMeterRegistry meterRegistry;
  private MobileVerificationService service;

  /** Captures the dispatched code so a test can verify with it; production never retains one. */
  private static final class RecordingSender implements MobileVerificationSender {
    private String lastOtp;
    private RuntimeException failure;

    @Override
    public String send(String mobileE164, String otp) {
      if (failure != null) {
        throw failure;
      }
      this.lastOtp = otp;
      return "provider-ref-1";
    }
  }

  @BeforeEach
  void setUp() {
    properties = new RegistrationProperties();
    properties.getOtp().setHmacKeyVersion("v1");
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 11);
    properties.getOtp().setHmacKeyRing("v1:" + Base64.getEncoder().encodeToString(key));

    registrations = mock(RegistrationRepository.class);
    learners = mock(LearnerRepository.class);
    otpHmac = new OtpHmac(properties);
    sender = new RecordingSender();
    ceilings = mock(AbuseCeiling.class);
    meterRegistry = new SimpleMeterRegistry();

    when(learners.provisionForSubject(anyString()))
        .thenReturn(new Learner(LEARNER_ID, SUBJECT, "ACTIVE", Instant.now(), Instant.now()));
    when(ceilings.consume(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact("asha@example.com", MOBILE, Instant.now(), null)));
    when(registrations.latestChallengeCreatedAt(LEARNER_ID)).thenReturn(Optional.empty());

    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation ->
        ((TransactionCallback<?>) invocation.getArgument(0))
            .doInTransaction(mock(TransactionStatus.class)));

    service = new MobileVerificationService(learners, registrations, properties, otpHmac, sender,
        ceilings, meterRegistry, transactions);
  }

  private RegistrationRepository.Challenge challenge(String otp, int attemptCount,
      Instant expiresAt, Instant consumedAt, Instant supersededAt, Instant verifiedAt) {
    return new RegistrationRepository.Challenge(CHALLENGE_ID, MOBILE,
        otpHmac.calculate("v1", CHALLENGE_ID, MOBILE, otp), "v1", attemptCount, 5,
        expiresAt, consumedAt, supersededAt, verifiedAt);
  }

  private void givenChallenge(RegistrationRepository.Challenge challenge) {
    when(registrations.lockChallengeForVerification(CHALLENGE_ID, LEARNER_ID))
        .thenReturn(Optional.of(challenge));
  }

  private static void assertCode(String expected, org.junit.jupiter.api.function.Executable call) {
    assertThatThrownBy(call::execute)
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo(expected);
  }

  // -------------------------------------------------------------------------------------------
  // Preconditions: a JIT-provisioned ACTIVE learner cannot bypass registration
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a learner with no registration record cannot request a code")
  void jitProvisionedLearnerCannotRequestACode() {
    // core.learner.status is ACTIVE for every learner Keycloak authenticates, including one who
    // never registered. Gating on that status instead of on the contact row would admit them.
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.empty());
    assertCode("REGISTRATION_REQUIRED", () -> service.send(SUBJECT));
    assertThat(sender.lastOtp).isNull();
  }

  @Test
  @DisplayName("an unverified email blocks mobile verification")
  void unverifiedEmailBlocksSending() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact("asha@example.com", MOBILE, null, null)));
    assertCode("EMAIL_VERIFICATION_REQUIRED", () -> service.send(SUBJECT));
    assertThat(sender.lastOtp).isNull();
  }

  // -------------------------------------------------------------------------------------------
  // Send: budgets, cooldown, supersede, provider failure
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a send supersedes any open challenge and never returns the code")
  void sendSupersedesOpenChallengesAndWithholdsTheCode() {
    MobileVerificationService.SendResponse response = service.send(SUBJECT);

    verify(registrations).supersedeOpenChallenges(LEARNER_ID);
    verify(registrations).insertChallenge(any(), eq(LEARNER_ID), eq(MOBILE), any(), eq("v1"),
        eq(5), eq("otp-v1"), any());
    assertThat(sender.lastOtp).hasSize(6).containsOnlyDigits();
    // The response carries the envelope and never the code itself.
    assertThat(response.challengeId()).isNotNull();
    assertThat(response.expiresAt()).isAfter(Instant.now());
    assertThat(response.toString()).doesNotContain(sender.lastOtp);
  }

  @Test
  @DisplayName("each of the three send ceilings refuses independently")
  void eachSendCeilingRefuses() {
    for (String dimension : new String[] {"sms-subject:", "sms-mobile:", "sms-global"}) {
      setUp();
      when(ceilings.consume(
          org.mockito.ArgumentMatchers.startsWith(dimension), anyInt(), anyInt())).thenReturn(false);
      assertCode("MOBILE_SEND_RATE_LIMITED", () -> service.send(SUBJECT));
      assertThat(sender.lastOtp).as("no message is sent once a ceiling refuses").isNull();
    }
  }

  @Test
  @DisplayName("a resend inside the cooldown is refused")
  void resendInsideTheCooldownIsRefused() {
    when(registrations.latestChallengeCreatedAt(LEARNER_ID))
        .thenReturn(Optional.of(Instant.now().minusSeconds(5)));
    assertCode("MOBILE_RESEND_COOLDOWN", () -> service.send(SUBJECT));
    assertThat(sender.lastOtp).isNull();
  }

  @Test
  @DisplayName("a provider failure retires the challenge rather than leaving it open")
  void providerFailureAbandonsTheChallenge() {
    sender.failure = new IllegalStateException("gateway down");
    assertCode("SMS_PROVIDER_UNAVAILABLE", () -> service.send(SUBJECT));
    // Once at prepare time, once when abandoning: a code nobody received must not keep occupying
    // the learner's single open-challenge slot.
    verify(registrations, org.mockito.Mockito.times(2)).supersedeOpenChallenges(LEARNER_ID);
    verify(registrations).audit(any(), eq(LEARNER_ID), any(), eq("MOBILE_OTP_SEND_FAILED"),
        eq("FAILURE"), eq("SMS_PROVIDER_UNAVAILABLE"));
  }

  // -------------------------------------------------------------------------------------------
  // Verify: the full rejection matrix
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the correct code verifies and advances onboarding")
  void correctCodeVerifies() {
    givenChallenge(challenge("123456", 0, Instant.now().plusSeconds(300), null, null, null));

    MobileVerificationService.VerifyResponse response =
        service.verify(SUBJECT, CHALLENGE_ID, "123456");

    verify(registrations).recordVerifiedMobile(CHALLENGE_ID, LEARNER_ID, MOBILE);
    verify(registrations).audit(any(), eq(LEARNER_ID), eq(CHALLENGE_ID), eq("MOBILE_VERIFIED"),
        eq("SUCCESS"), any());
    assertThat(response.onboardingState()).isEqualTo("PROFILE_PENDING");
  }

  @Test
  @DisplayName("a wrong code is rejected and the attempt is counted")
  void wrongCodeIsCountedAndRejected() {
    givenChallenge(challenge("123456", 0, Instant.now().plusSeconds(300), null, null, null));

    assertCode("MOBILE_OTP_INVALID", () -> service.verify(SUBJECT, CHALLENGE_ID, "654321"));

    // The increment is the entire attempt ceiling. If the rejection rolled it back, an attacker
    // would get unlimited guesses at six digits.
    verify(registrations).recordFailedAttempt(CHALLENGE_ID);
    verify(registrations, never()).recordVerifiedMobile(any(), any(), any());
  }

  @Test
  @DisplayName("a wrong code raises the no-rollback subtype so the attempt count survives")
  void wrongCodeRaisesTheNoRollbackSubtype() throws Exception {
    givenChallenge(challenge("123456", 0, Instant.now().plusSeconds(300), null, null, null));

    assertThatThrownBy(() -> service.verify(SUBJECT, CHALLENGE_ID, "654321"))
        .isInstanceOf(InvalidOtpException.class);

    // The annotation is what makes the increment durable; asserting the exception type alone would
    // pass even if somebody removed it.
    var verify = MobileVerificationService.class.getDeclaredMethod(
        "verify", String.class, UUID.class, String.class);
    var transactional = verify.getAnnotation(
        org.springframework.transaction.annotation.Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.noRollbackFor()).contains(InvalidOtpException.class);
  }

  @Test
  @DisplayName("an expired code is refused")
  void expiredCodeIsRefused() {
    givenChallenge(challenge("123456", 0, Instant.now().minusSeconds(1), null, null, null));
    assertCode("MOBILE_CHALLENGE_UNAVAILABLE", () -> service.verify(SUBJECT, CHALLENGE_ID, "123456"));
    verify(registrations, never()).recordVerifiedMobile(any(), any(), any());
  }

  @Test
  @DisplayName("a consumed code cannot be replayed")
  void consumedCodeCannotBeReplayed() {
    givenChallenge(challenge("123456", 1, Instant.now().plusSeconds(300), Instant.now(), null, null));
    assertCode("MOBILE_CHALLENGE_UNAVAILABLE", () -> service.verify(SUBJECT, CHALLENGE_ID, "123456"));
    verify(registrations, never()).recordVerifiedMobile(any(), any(), any());
  }

  @Test
  @DisplayName("a superseded code is refused after a newer one was requested")
  void supersededCodeIsRefused() {
    givenChallenge(challenge("123456", 0, Instant.now().plusSeconds(300), null, Instant.now(), null));
    assertCode("MOBILE_CHALLENGE_UNAVAILABLE", () -> service.verify(SUBJECT, CHALLENGE_ID, "123456"));
  }

  @Test
  @DisplayName("an exhausted attempt ceiling refuses even the correct code")
  void exhaustedAttemptsRefusesEvenTheCorrectCode() {
    givenChallenge(challenge("123456", 5, Instant.now().plusSeconds(300), null, null, null));
    assertCode("MOBILE_CHALLENGE_UNAVAILABLE", () -> service.verify(SUBJECT, CHALLENGE_ID, "123456"));
    verify(registrations, never()).recordVerifiedMobile(any(), any(), any());
  }

  @Test
  @DisplayName("re-submitting an already verified code is idempotent, not an error")
  void alreadyVerifiedChallengeIsIdempotent() {
    givenChallenge(challenge("123456", 0, Instant.now().plusSeconds(300), Instant.now(), null,
        Instant.now()));
    MobileVerificationService.VerifyResponse response =
        service.verify(SUBJECT, CHALLENGE_ID, "123456");
    assertThat(response.onboardingState()).isEqualTo("PROFILE_PENDING");
  }

  @Test
  @DisplayName("a challenge belonging to another learner reads as absent")
  void crossUserChallengeIsRefused() {
    // The repository filters on the authenticated learner, so another learner's challenge returns
    // empty rather than being read and then rejected.
    when(registrations.lockChallengeForVerification(CHALLENGE_ID, LEARNER_ID))
        .thenReturn(Optional.empty());
    assertCode("MOBILE_CHALLENGE_UNAVAILABLE", () -> service.verify(SUBJECT, CHALLENGE_ID, "123456"));
  }

  @Test
  @DisplayName("a malformed code is refused without touching the challenge")
  void malformedCodeIsRefusedWithoutALookup() {
    assertThatThrownBy(() -> service.verify(SUBJECT, CHALLENGE_ID, "abcdef"))
        .isInstanceOf(InvalidOtpException.class);
    assertThatThrownBy(() -> service.verify(SUBJECT, CHALLENGE_ID, "12345"))
        .isInstanceOf(InvalidOtpException.class);
    assertThatThrownBy(() -> service.verify(SUBJECT, CHALLENGE_ID, null))
        .isInstanceOf(InvalidOtpException.class);
    verify(registrations, never()).lockChallengeForVerification(any(), any());
  }

  @Test
  @DisplayName("the per-subject verify ceiling refuses before the challenge is read")
  void verifyCeilingRefusesBeforeReadingTheChallenge() {
    when(ceilings.consume(org.mockito.ArgumentMatchers.startsWith("otp-verify:"),
        anyInt(), anyInt())).thenReturn(false);
    assertCode("MOBILE_OTP_RATE_LIMITED", () -> service.verify(SUBJECT, CHALLENGE_ID, "123456"));
    verify(registrations, never()).lockChallengeForVerification(any(), any());
  }

  @Test
  @DisplayName("throttles advertise the window or cooldown actually configured")
  void throttlesAdvertiseRealPolicy() {
    when(ceilings.consume(org.mockito.ArgumentMatchers.startsWith("sms-subject:"), anyInt(),
        anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.send(SUBJECT))
        .isInstanceOf(RegistrationException.class)
        .satisfies(failure -> assertThat(((RegistrationException) failure).retryAfterSeconds())
            .as("send window is an hour").isEqualTo(3600L));

    setUp();
    when(registrations.latestChallengeCreatedAt(LEARNER_ID))
        .thenReturn(Optional.of(Instant.now().minusSeconds(5)));
    assertThatThrownBy(() -> service.send(SUBJECT))
        .isInstanceOf(RegistrationException.class)
        .satisfies(failure -> assertThat(((RegistrationException) failure).retryAfterSeconds())
            .as("cooldown hint must track the configured value, not a hardcoded 60")
            .isEqualTo(properties.getOtp().getResendCooldownSeconds()));

    setUp();
    when(ceilings.consume(org.mockito.ArgumentMatchers.startsWith("otp-verify:"), anyInt(),
        anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.verify(SUBJECT, CHALLENGE_ID, "123456"))
        .isInstanceOf(RegistrationException.class)
        .satisfies(failure -> assertThat(((RegistrationException) failure).retryAfterSeconds())
            .isEqualTo(300L));
  }

  @Test
  @DisplayName("a non-default cooldown is reflected in the retry hint")
  void configuredCooldownIsReflected() {
    properties.getOtp().setResendCooldownSeconds(90);
    when(registrations.latestChallengeCreatedAt(LEARNER_ID))
        .thenReturn(Optional.of(Instant.now().minusSeconds(5)));
    assertThatThrownBy(() -> service.send(SUBJECT))
        .isInstanceOf(RegistrationException.class)
        .satisfies(failure -> assertThat(((RegistrationException) failure).retryAfterSeconds())
            .isEqualTo(90L));
  }

  @Test
  @DisplayName("a refusal that is not a throttle advertises no retry hint")
  void nonThrottleRefusalsHaveNoRetryHint() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.send(SUBJECT))
        .isInstanceOf(RegistrationException.class)
        .satisfies(failure -> assertThat(((RegistrationException) failure).retryAfterSeconds())
            .isZero());
  }

  @Test
  @DisplayName("every unusable-challenge refusal is indistinguishable to the caller")
  void refusalsDoNotDistinguishTheReason() {
    // A caller who could tell "expired" from "already used" from "wrong" would learn whether they
    // were racing a live challenge.
    String wrongCode = new InvalidOtpException().detail();
    String unavailable = RegistrationException.challengeUnavailable("expired").detail();
    assertThat(wrongCode).isEqualTo(unavailable);
  }
}
