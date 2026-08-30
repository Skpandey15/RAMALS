package io.ramals.learningplatform.registration;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.observability.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mobile ownership verification for professional onboarding.
 *
 * <p>An ownership check, not an authentication factor: it never sets {@code amr}, raises
 * {@code acr}, satisfies {@code MfaAuthorization} or touches TOTP (M1-ADR-015). It follows that
 * Keycloak's brute-force protection does not apply here, so the attempt ceiling, the cooldown and
 * the per-dimension budgets below are the whole control rather than a supplement to one.
 *
 * <p>Provider calls sit outside the database transaction.
 */
@Service
class MobileVerificationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(MobileVerificationService.class);

  /** Bounds guessing per subject independently of any single challenge's attempt ceiling. */
  private static final int VERIFY_ATTEMPT_LIMIT = 30;
  private static final int VERIFY_ATTEMPT_WINDOW_SECONDS = 300;
  private static final int SEND_LIMIT_PER_WINDOW = 5;
  private static final int SEND_WINDOW_SECONDS = 3600;
  private static final int OTP_BOUND = 1_000_000;

  private final LearnerRepository learners;
  private final RegistrationRepository registrations;
  private final RegistrationProperties properties;
  private final OtpHmac otpHmac;
  private final MobileVerificationSender sender;
  private final AbuseCeiling ceilings;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate transactions;
  private final SecureRandom random = new SecureRandom();

  MobileVerificationService(LearnerRepository learners, RegistrationRepository registrations,
      RegistrationProperties properties, OtpHmac otpHmac, MobileVerificationSender sender,
      AbuseCeiling ceilings, MeterRegistry meterRegistry, TransactionTemplate transactions) {
    this.learners = learners;
    this.registrations = registrations;
    this.properties = properties;
    this.otpHmac = otpHmac;
    this.sender = sender;
    this.ceilings = ceilings;
    this.meterRegistry = meterRegistry;
    this.transactions = transactions;
  }

  /**
   * Issues a challenge and sends the code. The plaintext code exists only as a local and the
   * sender's argument; the caller gets the challenge id and timing envelope, never the code.
   */
  SendResponse send(String subject) {
    PendingChallenge pending = transactions.execute(status -> prepare(subject));
    try {
      String providerReference = sender.send(pending.mobile(), pending.otp());
      if (providerReference != null) {
        registrations.recordProviderReference(pending.response().challengeId(), providerReference);
      }
    } catch (RegistrationException failure) {
      abandon(pending, failure.code());
      throw failure;
    } catch (RuntimeException failure) {
      abandon(pending, "SMS_PROVIDER_UNAVAILABLE");
      throw RegistrationException.smsProviderUnavailable(failure);
    }
    registrations.audit(null, pending.learnerId(), pending.response().challengeId(),
        "MOBILE_OTP_SENT", "SUCCESS", null);
    BusinessEventLogger.info(LOGGER, "mobile.otp.sent", "Mobile verification code dispatched",
        Map.of("learnerId", pending.learnerId(), "challengeId", pending.response().challengeId(),
            "provider", properties.getSms().getProvider(), "outcome", "SUCCESS"));
    meterRegistry.counter("ramals.registration.otp.sends",
        "provider", properties.getSms().getProvider(), "outcome", "success").increment();
    return pending.response();
  }

  /**
   * Retires a challenge whose code was never delivered. The cooldown still applies afterwards, so a
   * provider failure cannot be induced to issue challenges faster than the cooldown allows.
   */
  private void abandon(PendingChallenge pending, String reasonCode) {
    registrations.supersedeOpenChallenges(pending.learnerId());
    registrations.audit(null, pending.learnerId(), pending.response().challengeId(),
        "MOBILE_OTP_SEND_FAILED", "FAILURE", reasonCode);
    meterRegistry.counter("ramals.registration.otp.sends",
        "provider", properties.getSms().getProvider(), "outcome", "failure").increment();
  }

  private PendingChallenge prepare(String subject) {
    Learner learner = learners.provisionForSubject(subject);
    RegistrationRepository.Contact contact = registrations.findContact(learner.id())
        .orElseThrow(RegistrationException::registrationRequired);
    if (contact.emailVerifiedAt() == null) {
      // Ordering is a security property, not a convenience: an unverified email means nobody has
      // demonstrated control of the address this account was opened with, and sending SMS on its
      // behalf would let an unproven registration spend the gateway budget.
      throw RegistrationException.emailVerificationRequired();
    }
    enforceSendBudget(subject, contact.mobile());

    Instant now = Instant.now();
    int cooldownSeconds = properties.getOtp().getResendCooldownSeconds();
    registrations.latestChallengeCreatedAt(learner.id())
        .filter(created -> created.plusSeconds(cooldownSeconds).isAfter(now))
        .ifPresent(created -> {
          throw RegistrationException.resendCooldown(cooldownSeconds);
        });

    UUID challengeId = UuidV7.generate();
    String otp = "%06d".formatted(random.nextInt(OTP_BOUND));
    Instant expiresAt = now.plusSeconds(properties.getOtp().getTtlSeconds());
    // Superseding first means at most one challenge is ever open for a learner, so a code from an
    // earlier send cannot be used after a newer one was requested.
    registrations.supersedeOpenChallenges(learner.id());
    registrations.insertChallenge(challengeId, learner.id(), contact.mobile(),
        otpHmac.calculate(otpHmac.currentVersion(), challengeId, contact.mobile(), otp),
        otpHmac.currentVersion(), properties.getOtp().getMaxAttempts(),
        properties.getOtp().getPolicyVersion(), expiresAt);
    return new PendingChallenge(learner.id(), contact.mobile(), otp,
        new SendResponse(challengeId, expiresAt, now.plusSeconds(cooldownSeconds)));
  }

  /**
   * Applies the three send ceilings: per subject, per number, and per deployment. The first two
   * bound an individual abuser; the third bounds the bill, because an attacker who can mint accounts
   * gets a fresh per-subject counter each time. All three dimensions are hashed before use.
   */
  private void enforceSendBudget(String subject, String mobile) {
    if (!ceilings.consume("sms-subject:" + subject, SEND_LIMIT_PER_WINDOW, SEND_WINDOW_SECONDS)) {
      throw RegistrationException.mobileSendRateLimited("subject", SEND_WINDOW_SECONDS);
    }
    if (!ceilings.consume("sms-mobile:" + mobile, SEND_LIMIT_PER_WINDOW, SEND_WINDOW_SECONDS)) {
      throw RegistrationException.mobileSendRateLimited("mobile", SEND_WINDOW_SECONDS);
    }
    if (!ceilings.consume("sms-global", properties.getSms().getGlobalHourlyBudget(),
        SEND_WINDOW_SECONDS)) {
      throw RegistrationException.mobileSendRateLimited("global", SEND_WINDOW_SECONDS);
    }
  }

  /**
   * Verifies a submitted code.
   *
   * <p>{@code noRollbackFor} is what makes the attempt increment durable: under the default rule the
   * rejection would undo it and the ceiling would never advance.
   *
   * <p>Every unusable-challenge condition raises the same code and wording as a wrong code, so a
   * caller cannot tell whether they are racing a live challenge.
   */
  @Transactional(noRollbackFor = InvalidOtpException.class)
  VerifyResponse verify(String subject, UUID challengeId, String otp) {
    if (otp == null || !otp.matches("\\d{6}")) {
      throw new InvalidOtpException();
    }
    Learner learner = learners.provisionForSubject(subject);
    // Charged in its own transaction, so the rejections below cannot roll the increment back.
    if (!ceilings.consume("otp-verify:" + subject, VERIFY_ATTEMPT_LIMIT,
        VERIFY_ATTEMPT_WINDOW_SECONDS)) {
      throw RegistrationException.otpVerifyRateLimited(VERIFY_ATTEMPT_WINDOW_SECONDS);
    }
    RegistrationRepository.Challenge challenge = registrations
        .lockChallengeForVerification(challengeId, learner.id())
        .orElseThrow(() -> RegistrationException.challengeUnavailable("unknown or not owned"));

    if (challenge.verifiedAt() != null) {
      // Idempotent success: re-submitting the code that already verified this challenge returns the
      // same answer rather than a confusing failure, which matters when a response is lost in transit.
      return new VerifyResponse("PROFILE_PENDING", "PROFESSIONAL_PROFILE");
    }
    if (challenge.consumedAt() != null) {
      throw RegistrationException.challengeUnavailable("already consumed");
    }
    if (challenge.supersededAt() != null) {
      throw RegistrationException.challengeUnavailable("superseded by a newer challenge");
    }
    if (!challenge.expiresAt().isAfter(Instant.now())) {
      throw RegistrationException.challengeUnavailable("expired");
    }
    if (challenge.attemptCount() >= challenge.maxAttempts()) {
      throw RegistrationException.challengeUnavailable("attempt ceiling exhausted");
    }

    byte[] submitted = otpHmac.calculate(
        challenge.keyVersion(), challenge.id(), challenge.mobile(), otp);
    if (!otpHmac.matches(challenge.otpHmac(), submitted)) {
      registrations.recordFailedAttempt(challenge.id());
      registrations.audit(null, learner.id(), challenge.id(), "MOBILE_VERIFICATION_FAILED",
          "FAILURE", "OTP_MISMATCH");
      BusinessEventLogger.warn(LOGGER, "mobile.otp.rejected",
          "Mobile verification code did not match",
          Map.of("learnerId", learner.id(), "challengeId", challenge.id(),
              "attemptCount", challenge.attemptCount() + 1, "outcome", "FAILURE"));
      meterRegistry.counter("ramals.registration.otp.verifications", "outcome", "failure")
          .increment();
      throw new InvalidOtpException();
    }

    registrations.recordVerifiedMobile(challenge.id(), learner.id(), challenge.mobile());
    registrations.audit(null, learner.id(), challenge.id(), "MOBILE_VERIFIED", "SUCCESS", null);
    BusinessEventLogger.info(LOGGER, "mobile.verified", "Mobile ownership verified",
        Map.of("learnerId", learner.id(), "challengeId", challenge.id(), "outcome", "SUCCESS"));
    meterRegistry.counter("ramals.registration.otp.verifications", "outcome", "success").increment();
    return new VerifyResponse("PROFILE_PENDING", "PROFESSIONAL_PROFILE");
  }

  /** The plaintext code lives only inside a {@code send} call, on the stack, and nowhere else. */
  private record PendingChallenge(UUID learnerId, String mobile, String otp,
      SendResponse response) {
  }

  record SendResponse(UUID challengeId, Instant expiresAt, Instant resendAfter) {
  }

  record VerifyResponse(String onboardingState, String nextStep) {
  }
}
