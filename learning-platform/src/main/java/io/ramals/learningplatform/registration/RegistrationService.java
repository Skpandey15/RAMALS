package io.ramals.learningplatform.registration;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates professional self-registration across Keycloak and the RAMALS identity boundary.
 *
 * <p>There is no distributed transaction, and the ordering assumes it: claim an idempotent
 * operation row, call the provider, persist, request the mail, then mark the operation finished.
 * Every step is safe to repeat, so any interruption leaves a state a later attempt resumes from.
 *
 * <p>The provider call sits outside the database transaction, or a Keycloak stall would hold a
 * connection and row locks for its duration.
 *
 * <p>The password is a parameter and never a field: not in the fingerprint, the operation row, an
 * audit event, a log or a metric tag.
 */
@Service
class RegistrationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationService.class);

  /** Ceiling per normalized email per hour: enough for genuine retries, not enough to mail-bomb. */
  private static final int EMAIL_ATTEMPT_LIMIT = 5;
  private static final int EMAIL_ATTEMPT_WINDOW_SECONDS = 3600;
  /**
   * Resend ceiling per normalized email per hour.
   *
   * <p>Lower than {@link #EMAIL_ATTEMPT_LIMIT} and counted on its own dimension. Its own, because
   * sharing the registration counter would let a resend exhaust a genuine registration's allowance
   * for an address the caller does not own. Lower, because a resend needs no form filled in, so it
   * is the cheaper of the two to abuse as a mailer.
   */
  private static final int RESEND_ATTEMPT_LIMIT = 3;
  private static final int RESEND_ATTEMPT_WINDOW_SECONDS = 3600;
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

  private final RegistrationProperties properties;
  private final RegistrationRepository registrations;
  private final LearnerRepository learners;
  private final IdentityProviderPort identities;
  private final PhoneNormalizer phones;
  private final AbuseCeiling ceilings;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate transactions;

  RegistrationService(RegistrationProperties properties, RegistrationRepository registrations,
      LearnerRepository learners, IdentityProviderPort identities, PhoneNormalizer phones,
      AbuseCeiling ceilings, MeterRegistry meterRegistry, TransactionTemplate transactions) {
    this.properties = properties;
    this.registrations = registrations;
    this.learners = learners;
    this.identities = identities;
    this.phones = phones;
    this.ceilings = ceilings;
    this.meterRegistry = meterRegistry;
    this.transactions = transactions;
  }

  RegistrationResponse register(String idempotencyKey, RegistrationRequest request) {
    if (!properties.isEnabled()) {
      throw RegistrationException.disabled();
    }
    String key = requireBoundedKey(idempotencyKey);
    requireMatchingPasswordConfirmation(request);
    requireServerKnownConsent(request);

    String email = request.email().trim().toLowerCase(Locale.ROOT);
    String mobile = phones.normalize(request.mobileNumber(), request.country());

    // Claim the operation before charging quota. start() is idempotent per key and verifies the
    // fingerprint, so a replay of a *completed* operation is identified here and answered without
    // consuming a new-attempt allowance - a client retrying its own success must not eventually be
    // throttled for it. A key replayed with a different body still fails inside start().
    RegistrationRepository.Operation operation =
        registrations.start(key, fingerprint(request, email, mobile));
    if ("EMAIL_PENDING".equals(operation.status())) {
      // A completed operation replayed with its own key. Answer identically without repeating any
      // side effect: no second identity, no second contact row, no second verification mail.
      BusinessEventLogger.info(LOGGER, "registration.replayed",
          "Registration replayed for a completed operation",
          Map.of("operationId", operation.id(), "outcome", "SUCCESS"));
      count("replayed", "NONE");
      return new RegistrationResponse(operation.id(), "EMAIL_VERIFICATION");
    }

    if (!ceilings.consume(
        "registration-email:" + email, EMAIL_ATTEMPT_LIMIT, EMAIL_ATTEMPT_WINDOW_SECONDS)) {
      count("rate_limited", "REGISTRATION_RATE_LIMITED");
      throw RegistrationException.registrationRateLimited("email", EMAIL_ATTEMPT_WINDOW_SECONDS);
    }

    registrations.audit(operation.id(), null, null, "LEARNER_REGISTRATION_STARTED", "SUCCESS", null);
    try {
      return complete(operation, request, email, mobile);
    } catch (RegistrationException rejected) {
      registrations.markFailedRecoverable(operation.id());
      registrations.audit(operation.id(), null, null, "LEARNER_REGISTRATION_FAILED", "FAILURE",
          rejected.code());
      count("failure", rejected.code());
      throw rejected;
    } catch (RuntimeException unexpected) {
      registrations.markFailedRecoverable(operation.id());
      registrations.audit(operation.id(), null, null, "LEARNER_REGISTRATION_FAILED", "FAILURE",
          "UNEXPECTED_ERROR");
      count("failure", "UNEXPECTED_ERROR");
      throw unexpected;
    }
  }

  /**
   * Re-sends the provider's verification mail for an address that has not completed verification.
   *
   * <p>This route exists because the two behaviours around it combine into a dead end. Verification
   * mail is the only way to finish registering, and {@link #complete} deliberately sends none when
   * the identity already exists — so a learner whose mail was lost cannot recover by registering
   * again, and previously had no route at all. That was closed by hand against the provider's admin
   * API, which is not a thing a learner can do.
   *
   * <p>Every path returns the same value. Unknown address, already-verified address, address that
   * exists and was mailed: all indistinguishable to the caller, because the response is the only
   * signal a caller gets and any variation in it is an account-enumeration oracle. That is also why
   * quota is charged <em>before</em> the provider is consulted — a rate-limit rejection that could
   * only be reached for addresses that exist would reintroduce the oracle in the timing and the
   * status code, having removed it from the body. The distinction is recorded in audit and metrics,
   * where an operator can see it and an anonymous caller cannot.
   */
  ResendVerificationResponse resendVerification(ResendVerificationRequest request) {
    if (!properties.isEnabled()) {
      throw RegistrationException.disabled();
    }
    String email = request.email().trim().toLowerCase(Locale.ROOT);

    // Charged first, and on every call, so the ceiling does not depend on whether the address
    // resolves. See the class comment above on why that ordering is the security property.
    if (!ceilings.consume(
        "registration-resend:" + email, RESEND_ATTEMPT_LIMIT, RESEND_ATTEMPT_WINDOW_SECONDS)) {
      countResend("rate_limited");
      throw RegistrationException.registrationRateLimited("resend", RESEND_ATTEMPT_WINDOW_SECONDS);
    }

    // Empty covers both "no such identity" and "already verified"; the port refuses to say which.
    identities.unverifiedSubjectForEmail(email).ifPresentOrElse(
        subject -> {
          identities.sendVerificationEmail(subject);
          BusinessEventLogger.info(LOGGER, "registration.verification.resent",
              "Verification mail re-sent for an unverified identity",
              Map.of("emailFingerprint", emailFingerprint(email), "outcome", "SUCCESS"));
          countResend("sent");
        },
        () -> {
          // Not a warning: an unknown or already-verified address is an ordinary outcome here, and
          // logging it louder than a real send would rebuild the oracle inside the log file.
          BusinessEventLogger.info(LOGGER, "registration.verification.resend.noop",
              "Resend requested for an address with no unverified identity; nothing was sent",
              Map.of("emailFingerprint", emailFingerprint(email), "outcome", "SUCCESS"));
          countResend("noop");
        });

    return new ResendVerificationResponse("EMAIL_VERIFICATION");
  }

  /**
   * A stable, non-reversible handle for an address, so operators can correlate repeated resends.
   *
   * <p>The address itself is never logged. Reuses the same digest the registration fingerprint is
   * built from, so the two can be joined without either carrying the plaintext.
   */
  private static String emailFingerprint(String email) {
    return RegistrationRepository.sha256("resend:" + email).substring(0, 16);
  }

  private void countResend(String outcome) {
    meterRegistry.counter("ramals.registration.resend", "outcome", outcome).increment();
  }

  /** The acknowledgement. Carries no operation id: there is no operation row to point at. */
  record ResendVerificationResponse(String status) {
  }

  /**
   * Runs the provider call, the persistence step and the verification mail.
   *
   * <p>The refusal to persist against a pre-existing identity is the security control here. When
   * Keycloak reports the email already belongs to someone, we stop: writing the submitted name and
   * mobile would land them against the existing learner, and for a just-in-time learner with no
   * contact row the insert would succeed. The response is shaped identically either way, so this
   * does not become an oracle for whether an account exists; the distinction is recorded in audit.
   */
  private RegistrationResponse complete(RegistrationRepository.Operation operation,
      RegistrationRequest request, String email, String mobile) {
    IdentityProviderPort.Identity identity =
        identities.createLearner(operation.id().toString(), request);

    if (!identity.createdByThisOperation()) {
      registrations.audit(operation.id(), null, null, "LEARNER_REGISTRATION_DUPLICATE", "REJECTED",
          "IDENTITY_ALREADY_EXISTS");
      BusinessEventLogger.warn(LOGGER, "registration.duplicate.ignored",
          "Registration matched an identity this operation did not create; no data was written",
          Map.of("operationId", operation.id(), "outcome", "REJECTED"));
      count("duplicate", "IDENTITY_ALREADY_EXISTS");
      registrations.markEmailRequested(operation.id());
      return new RegistrationResponse(operation.id(), "EMAIL_VERIFICATION");
    }

    registrations.identityCreated(operation.id(), identity.subject());
    UUID learnerId = transactions.execute(status -> persist(operation.id(), identity.subject(),
        request, email, mobile));

    identities.sendVerificationEmail(identity.subject());
    registrations.markEmailRequested(operation.id());
    registrations.audit(operation.id(), learnerId, null, "EMAIL_VERIFICATION_REQUIRED", "SUCCESS",
        null);
    BusinessEventLogger.info(LOGGER, "registration.completed",
        "Professional registration accepted; email verification requested",
        Map.of("operationId", operation.id(), "learnerId", learnerId, "outcome", "SUCCESS"));
    count("success", "NONE");
    return new RegistrationResponse(operation.id(), "EMAIL_VERIFICATION");
  }

  private UUID persist(UUID operationId, String subject, RegistrationRequest request, String email,
      String mobile) {
    Learner learner = learners.provisionForSubject(subject);
    RegistrationProperties.Consent consent = properties.getConsent();
    registrations.complete(operationId, learner.id(), new RegistrationRepository.RegistrationData(
        request.firstName().trim(), request.lastName().trim(), email, mobile,
        request.country().toUpperCase(Locale.ROOT), blankToNull(request.city()),
        consent.getTermsVersion(), consent.getTermsRef(), consent.getPrivacyVersion(),
        consent.getPrivacyRef(), consent.getAdultStatementVersion()));
    registrations.audit(operationId, learner.id(), null, "IDENTITY_CREATED", "SUCCESS", null);
    return learner.id();
  }

  private static String requireBoundedKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()
        || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw RegistrationException.idempotencyKeyInvalid();
    }
    return idempotencyKey.trim();
  }

  /**
   * Compares the password with its confirmation in constant time - not because the confirmation is
   * a secret, but so credential comparison in this package never uses the short-circuiting one.
   */
  private static void requireMatchingPasswordConfirmation(RegistrationRequest request) {
    if (!MessageDigest.isEqual(
        request.password().getBytes(StandardCharsets.UTF_8),
        request.confirmPassword().getBytes(StandardCharsets.UTF_8))) {
      throw RegistrationException.passwordConfirmationMismatch();
    }
  }

  /**
   * Rejects consent versions this deployment did not issue. The learner echoes the version they were
   * shown; the values written to the database are the server's own, never the submitted strings.
   */
  private void requireServerKnownConsent(RegistrationRequest request) {
    RegistrationProperties.Consent consent = properties.getConsent();
    boolean accepted = request.termsAccepted() && request.privacyAccepted()
        && request.adultConfirmed();
    boolean known = consent.getTermsVersion().equals(request.termsVersion())
        && consent.getPrivacyVersion().equals(request.privacyVersion())
        && consent.getAdultStatementVersion().equals(request.adultStatementVersion());
    if (!accepted || !known) {
      throw RegistrationException.consentVersionUnknown();
    }
  }

  /**
   * A stable hash of the request's meaningful content, used to detect a replayed Idempotency-Key
   * carrying a different body. The password is excluded: it would put a credential-derived value in
   * a durable column and adds nothing.
   */
  private static String fingerprint(RegistrationRequest request, String email, String mobile) {
    return RegistrationRepository.sha256(String.join(" ",
        email, mobile, request.firstName().trim(), request.lastName().trim(),
        request.country().toUpperCase(Locale.ROOT), nullToEmpty(request.city()),
        request.termsVersion(), request.privacyVersion(), request.adultStatementVersion()));
  }

  /**
   * One counter for every registration outcome. Both tags are bounded server-side vocabularies:
   * email, mobile, address or subject would multiply series per learner and put contact data in the
   * metrics store.
   */
  private void count(String outcome, String code) {
    meterRegistry.counter("ramals.registration.attempts", "outcome", outcome, "code", code)
        .increment();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  record RegistrationResponse(UUID operationId, String nextStep) {
  }
}
