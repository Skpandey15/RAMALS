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
 * <p><strong>There is no distributed transaction here, and the design assumes it.</strong> Creating
 * an identity in Keycloak and writing contact data in PostgreSQL are two systems that can each
 * succeed while the other fails. Rather than pretend otherwise, the flow is ordered so that every
 * interruption leaves a state a later attempt can resume from: claim an idempotent operation row
 * first, call the provider second, persist third, request the verification mail last, and only then
 * mark the operation finished. Each step is safe to repeat.
 *
 * <p><strong>The provider call sits outside the database transaction</strong> (§27). A Keycloak stall
 * inside an open transaction would hold a connection and row locks for the length of the stall, so a
 * provider slowdown would become database exhaustion. The transaction opens after the provider has
 * answered and closes before the mail is requested.
 *
 * <p><strong>The password is a parameter and never a field.</strong> It arrives on
 * {@link RegistrationRequest}, is compared against its confirmation, is handed to the provider
 * adapter, and is referenced nowhere else — not in the fingerprint, not in the operation row, not in
 * an audit event, not in a log, not in the metric tags. {@code RegistrationRequest#toString} is
 * redacted so that even an accidental interpolation cannot disclose it.
 */
@Service
class RegistrationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationService.class);

  /** Ceiling per normalized email per hour: enough for genuine retries, not enough to mail-bomb. */
  private static final int EMAIL_ATTEMPT_LIMIT = 5;
  private static final int EMAIL_ATTEMPT_WINDOW_SECONDS = 3600;
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

  private final RegistrationProperties properties;
  private final RegistrationRepository registrations;
  private final LearnerRepository learners;
  private final IdentityProviderPort identities;
  private final PhoneNormalizer phones;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate transactions;

  RegistrationService(RegistrationProperties properties, RegistrationRepository registrations,
      LearnerRepository learners, IdentityProviderPort identities, PhoneNormalizer phones,
      MeterRegistry meterRegistry, TransactionTemplate transactions) {
    this.properties = properties;
    this.registrations = registrations;
    this.learners = learners;
    this.identities = identities;
    this.phones = phones;
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

    if (!registrations.withinCeiling(
        "registration-email:" + email, EMAIL_ATTEMPT_LIMIT, EMAIL_ATTEMPT_WINDOW_SECONDS)) {
      count("rate_limited", "REGISTRATION_RATE_LIMITED");
      throw RegistrationException.registrationRateLimited("email");
    }

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
   * Runs the provider call, the persistence step and the verification mail.
   *
   * <p><strong>The refusal to persist against a pre-existing identity is the security control in this
   * method.</strong> When Keycloak reports that the email already belongs to someone — as opposed to
   * confirming that this very operation created it — we stop. We do not write the submitted name,
   * mobile number or consent record, because they would land against the existing learner's id. That
   * matters most for a learner who was provisioned just-in-time and has no contact row yet: the
   * insert would succeed, and an attacker who merely knows a victim's email address would have
   * written their own mobile number into the victim's account.
   *
   * <p>The response is the same shape as a genuine registration, deliberately. Answering differently
   * would turn this endpoint into an oracle for "does an account exist for this address", which is
   * exactly the enumeration primitive an unauthenticated route must not offer. The distinction is
   * recorded in the audit trail, where it is available to an operator and not to a caller.
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
   * Compares the password with its confirmation in constant time.
   *
   * <p>Constant time not because the confirmation is a secret an attacker is guessing — they supplied
   * both halves — but because {@code String.equals} short-circuits on the first differing character,
   * and using it here would establish the habit of comparing credential material with the fast
   * comparison in a package where several other comparisons genuinely must not.
   */
  private static void requireMatchingPasswordConfirmation(RegistrationRequest request) {
    if (!MessageDigest.isEqual(
        request.password().getBytes(StandardCharsets.UTF_8),
        request.confirmPassword().getBytes(StandardCharsets.UTF_8))) {
      throw RegistrationException.passwordConfirmationMismatch();
    }
  }

  /**
   * Rejects consent versions this deployment did not issue.
   *
   * <p>A boolean "I accept" is not evidence of what was accepted. The learner echoes the version of
   * each document they were shown, and it is checked against server-known values so that the stored
   * acceptance refers to a specific revision. The versions written to the database are the server's
   * own, never the submitted strings — a request cannot name a version that does not exist and have
   * it recorded as though it did.
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
   * carrying a different body.
   *
   * <p>The password is deliberately excluded. Including it would place a credential-derived value in
   * a durable column, and it adds nothing: two requests differing only by password are a client
   * defect this check is not the right place to catch.
   */
  private static String fingerprint(RegistrationRequest request, String email, String mobile) {
    return RegistrationRepository.sha256(String.join(" ",
        email, mobile, request.firstName().trim(), request.lastName().trim(),
        request.country().toUpperCase(Locale.ROOT), nullToEmpty(request.city()),
        request.termsVersion(), request.privacyVersion(), request.adultStatementVersion()));
  }

  /**
   * One counter for every registration outcome.
   *
   * <p>Both tags are bounded server-side vocabularies. §22 forbids email, mobile, address or OIDC
   * subject as label values: those are unbounded, so they would multiply the time series per learner
   * and put contact data in the metrics store, which is neither access-controlled as PII nor covered
   * by the retention rules that apply to {@code identity.learner_contact}.
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
