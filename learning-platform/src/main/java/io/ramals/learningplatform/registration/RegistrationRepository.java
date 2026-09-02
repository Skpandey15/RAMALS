package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for registration operations, contact PII, onboarding state, mobile challenges,
 * abuse counters and the registration audit trail.
 *
 * <p>One repository across five tables because they are a single consistency boundary: an
 * onboarding change is only meaningful with the contact row it describes. Plain JDBC because the
 * invariants here are database invariants - a partial unique index, an upsert that must not
 * overwrite, a FOR UPDATE that must actually lock.
 */
@Repository
class RegistrationRepository {

  private final JdbcTemplate jdbc;

  RegistrationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // -------------------------------------------------------------------------------------------
  // Registration operations
  // -------------------------------------------------------------------------------------------

  /**
   * Claims or replays a registration operation for an Idempotency-Key.
   *
   * <p>Insert-on-conflict-do-nothing then read, so concurrent requests with one key converge on one
   * row. A key replayed with a different body is refused rather than answered with the first
   * request's result.
   */
  Operation start(String key, String fingerprint) {
    jdbc.update("""
        INSERT INTO identity.registration_operation(id, idempotency_key, request_fingerprint, status)
        VALUES (?, ?, ?, 'STARTED')
        ON CONFLICT (idempotency_key) DO NOTHING
        """, UuidV7.generate(), key, fingerprint);
    Operation operation = findOperation(key)
        .orElseThrow(() -> new IllegalStateException("Registration operation vanished after insert."));
    if (!MessageDigest.isEqual(
        operation.fingerprint().getBytes(StandardCharsets.UTF_8),
        fingerprint.getBytes(StandardCharsets.UTF_8))) {
      throw RegistrationException.idempotencyKeyConflict();
    }
    return operation;
  }

  Optional<Operation> findOperation(String key) {
    return jdbc.query("""
        SELECT id, request_fingerprint, status, keycloak_subject
        FROM identity.registration_operation
        WHERE idempotency_key = ?
        """,
        (row, index) -> new Operation(
            row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getString(4)),
        key).stream().findFirst();
  }

  void identityCreated(UUID operationId, String subject) {
    jdbc.update("""
        UPDATE identity.registration_operation
        SET status = 'IDENTITY_CREATED', keycloak_subject = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """, subject, operationId);
  }

  /**
   * Records a failure a later attempt with the same key may resume from. Without it the row stays
   * at STARTED forever and FAILED_RECOVERABLE is unreachable.
   */
  void markFailedRecoverable(UUID operationId) {
    jdbc.update("""
        UPDATE identity.registration_operation
        SET status = 'FAILED_RECOVERABLE', updated_at = CURRENT_TIMESTAMP
        WHERE id = ? AND status IN ('STARTED', 'IDENTITY_CREATED')
        """, operationId);
  }

  // -------------------------------------------------------------------------------------------
  // Contact PII and onboarding state
  // -------------------------------------------------------------------------------------------

  /**
   * Writes the contact row and opens onboarding, never overwriting an existing one.
   *
   * <p>Both inserts are ON CONFLICT DO NOTHING: the second line of defence behind
   * {@code RegistrationService} refusing to persist for an identity it did not create.
   */
  void complete(UUID operationId, UUID learnerId, RegistrationData data) {
    jdbc.update("""
        INSERT INTO identity.learner_contact(
          learner_id, first_name, last_name, email_normalized, mobile_e164, country_code, city,
          terms_version, terms_document_ref, privacy_version, privacy_document_ref,
          terms_accepted_at, privacy_accepted_at, adult_statement_version, adult_confirmed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (learner_id) DO NOTHING
        """,
        learnerId, data.firstName(), data.lastName(), data.email(), data.mobile(), data.country(),
        data.city(), data.termsVersion(), data.termsRef(), data.privacyVersion(), data.privacyRef(),
        data.adultVersion());
    jdbc.update("""
        INSERT INTO identity.professional_onboarding(learner_id, onboarding_state)
        VALUES (?, 'EMAIL_PENDING')
        ON CONFLICT (learner_id) DO NOTHING
        """, learnerId);
  }

  /**
   * Marks the operation complete, once the provider has accepted the verification mail.
   *
   * <p>Separate from {@link #complete} because EMAIL_PENDING is what makes a replay a no-op. Setting
   * it before the mail was accepted would strand a learner whose retry short-circuits.
   */
  void markEmailRequested(UUID operationId) {
    jdbc.update("""
        UPDATE identity.registration_operation
        SET status = 'EMAIL_PENDING', updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """, operationId);
  }

  Optional<Contact> findContact(UUID learnerId) {
    return jdbc.query("""
        SELECT email_normalized, mobile_e164, email_verified_at, mobile_verified_at
        FROM identity.learner_contact
        WHERE learner_id = ?
        """,
        (row, index) -> new Contact(
            row.getString(1), row.getString(2),
            instant(row.getObject(3, OffsetDateTime.class)),
            instant(row.getObject(4, OffsetDateTime.class))),
        learnerId).stream().findFirst();
  }

  /**
   * The learner's onboarding state, or empty for a learner who never registered - a legitimate
   * answer, since ADR 0001 lets a learner exist from just-in-time provisioning alone.
   */
  Optional<String> findOnboardingState(UUID learnerId) {
    return jdbc.query(
        "SELECT onboarding_state FROM identity.professional_onboarding WHERE learner_id = ?",
        (row, index) -> row.getString(1), learnerId).stream().findFirst();
  }

  /**
   * Records trusted email verification and advances onboarding. The state update is guarded by the
   * states it may legally leave, so a late reconciliation cannot walk a learner backwards.
   */
  void markEmailVerified(UUID learnerId) {
    jdbc.update("""
        UPDATE identity.learner_contact
        SET email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE learner_id = ?
        """, learnerId);
    jdbc.update("""
        UPDATE identity.professional_onboarding
        SET onboarding_state = 'MOBILE_PENDING', version = version + 1, updated_at = CURRENT_TIMESTAMP
        WHERE learner_id = ? AND onboarding_state IN ('EMAIL_PENDING', 'EMAIL_VERIFIED')
        """, learnerId);
  }

  // -------------------------------------------------------------------------------------------
  // Mobile verification challenges
  // -------------------------------------------------------------------------------------------

  void supersedeOpenChallenges(UUID learnerId) {
    jdbc.update("""
        UPDATE identity.mobile_verification_challenge
        SET superseded_at = CURRENT_TIMESTAMP
        WHERE learner_id = ? AND consumed_at IS NULL AND superseded_at IS NULL
        """, learnerId);
  }

  Optional<Instant> latestChallengeCreatedAt(UUID learnerId) {
    return jdbc.query("""
        SELECT created_at FROM identity.mobile_verification_challenge
        WHERE learner_id = ? ORDER BY created_at DESC LIMIT 1
        """,
        (row, index) -> instant(row.getObject(1, OffsetDateTime.class)),
        learnerId).stream().findFirst();
  }

  /**
   * Inserts a challenge.
   *
   * <p>{@code expires_at} is converted to {@link OffsetDateTime}: the PostgreSQL driver cannot infer
   * a SQL type for {@link Instant} and throws at execution, so an Instant here passes every mocked
   * test and fails for the first learner who asks for a code.
   */
  void insertChallenge(UUID id, UUID learnerId, String mobile, byte[] otpHmac, String keyVersion,
      int maxAttempts, String policyVersion, Instant expiresAt) {
    jdbc.update("""
        INSERT INTO identity.mobile_verification_challenge(
          id, learner_id, mobile_e164, otp_hmac, hmac_key_version, max_attempts, policy_version,
          expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, id, learnerId, mobile, otpHmac, keyVersion, maxAttempts, policyVersion,
        OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
  }

  void recordProviderReference(UUID challengeId, String providerReference) {
    jdbc.update("""
        UPDATE identity.mobile_verification_challenge
        SET provider_message_ref = ?
        WHERE id = ?
        """, providerReference, challengeId);
  }

  /**
   * Loads a challenge for verification, locking the row.
   *
   * <p>The learner_id predicate is the cross-user control and belongs in the query: another
   * learner's challenge must read as absent, not be read and then rejected. FOR UPDATE serialises
   * concurrent verification of the same challenge.
   */
  Optional<Challenge> lockChallengeForVerification(UUID id, UUID learnerId) {
    return jdbc.query("""
        SELECT id, mobile_e164, otp_hmac, hmac_key_version, attempt_count, max_attempts,
               expires_at, consumed_at, superseded_at, verified_at
        FROM identity.mobile_verification_challenge
        WHERE id = ? AND learner_id = ?
        FOR UPDATE
        """,
        (row, index) -> new Challenge(
            row.getObject(1, UUID.class), row.getString(2), row.getBytes(3), row.getString(4),
            row.getInt(5), row.getInt(6),
            instant(row.getObject(7, OffsetDateTime.class)),
            instant(row.getObject(8, OffsetDateTime.class)),
            instant(row.getObject(9, OffsetDateTime.class)),
            instant(row.getObject(10, OffsetDateTime.class))),
        id, learnerId).stream().findFirst();
  }

  void recordFailedAttempt(UUID challengeId) {
    jdbc.update("""
        UPDATE identity.mobile_verification_challenge
        SET attempt_count = attempt_count + 1
        WHERE id = ? AND attempt_count < max_attempts
        """, challengeId);
  }

  /**
   * Claims the mobile number for this learner and closes the challenge.
   *
   * <p>Enforced by {@code uq_learner_contact_verified_mobile}. A read-then-write check could not do
   * this - both racing readers would see the number free.
   */
  void recordVerifiedMobile(UUID challengeId, UUID learnerId, String mobile) {
    try {
      jdbc.update("""
          UPDATE identity.learner_contact
          SET mobile_e164 = ?, mobile_verified_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
          WHERE learner_id = ?
          """, mobile, learnerId);
    } catch (DuplicateKeyException alreadyReserved) {
      throw RegistrationException.mobileAlreadyRegistered();
    }
    jdbc.update("""
        UPDATE identity.mobile_verification_challenge
        SET verified_at = CURRENT_TIMESTAMP, consumed_at = CURRENT_TIMESTAMP
        WHERE id = ? AND consumed_at IS NULL
        """, challengeId);
    jdbc.update("""
        UPDATE identity.professional_onboarding
        SET onboarding_state = 'PROFILE_PENDING', version = version + 1, updated_at = CURRENT_TIMESTAMP
        WHERE learner_id = ? AND onboarding_state IN ('MOBILE_PENDING', 'MOBILE_VERIFIED')
        """, learnerId);
  }

  // -------------------------------------------------------------------------------------------
  // Abuse counters
  // -------------------------------------------------------------------------------------------

  /**
   * Increments a fixed-window counter and reports whether the caller is still inside its ceiling.
   *
   * <p>In the database, not a local map: the service runs multiple replicas and a per-pod counter
   * throttles the accounting rather than the caller. Blocked requests still increment, so sustained
   * abuse costs the abuser the whole window. Dimensions are hashed, so no bucket key holds an email,
   * a number or an address. The window is epoch-aligned so every replica computes the same boundary
   * without coordinating; the trade is the usual fixed-window overshoot at a boundary.
   */
  boolean withinCeiling(String dimension, int limit, int windowSeconds) {
    long epochSecond = Instant.now().getEpochSecond();
    // One clock read, used twice. Reading the clock separately for the truncation and the modulus
    // let a request that crossed a second boundary between the two land in a bucket that matched
    // neither window.
    Instant windowStart = Instant.ofEpochSecond(epochSecond - Math.floorMod(epochSecond, windowSeconds));
    Integer count = jdbc.queryForObject("""
        INSERT INTO identity.abuse_counter(bucket_key, window_started_at, request_count)
        VALUES (?, ?, 1)
        ON CONFLICT (bucket_key, window_started_at)
        DO UPDATE SET request_count = identity.abuse_counter.request_count + 1
        RETURNING request_count
        """, Integer.class, sha256(dimension), OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
    return count != null && count <= limit;
  }

  /**
   * Records one accepted verification-resend request.
   *
   * <p>Called for every accepted request, including those with nothing to send, so that the work
   * done on the request path does not vary with whether the address resolved. {@code subject} is
   * null in that case; no address is written here or anywhere else on this path.
   */
  void enqueueVerificationResend(UUID id, String subject, Instant now) {
    jdbc.update("""
        INSERT INTO identity.verification_resend_outbox(id, subject, status, next_attempt_at, created_at)
        VALUES (?, ?, 'PENDING', ?, ?)
        """, id, subject, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
  }

  /**
   * Claims a batch of due resend work.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED}, matching the outbox in V025: replicas take disjoint rows
   * without coordinating, and a row a peer is already sending is skipped rather than sent twice.
   * The rows stay claimed for the life of the caller's transaction.
   */
  List<VerificationResend> claimDueVerificationResends(Instant now, int batchSize) {
    return jdbc.query("""
        SELECT id, subject, attempt_count
        FROM identity.verification_resend_outbox
        WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
        ORDER BY next_attempt_at, created_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """,
        (rs, rowNum) -> new VerificationResend(
            rs.getObject("id", UUID.class), rs.getString("subject"), rs.getInt("attempt_count")),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC), batchSize);
  }

  /** Removes delivered or no-op work. Retention is deliberately nil: nothing reads a done row. */
  void deleteVerificationResend(UUID id) {
    jdbc.update("DELETE FROM identity.verification_resend_outbox WHERE id = ?", id);
  }

  /** Schedules another attempt after a recoverable provider failure. */
  void rescheduleVerificationResend(UUID id, Instant nextAttemptAt, String errorCode) {
    jdbc.update("""
        UPDATE identity.verification_resend_outbox
        SET status = 'RETRY', attempt_count = attempt_count + 1,
            next_attempt_at = ?, last_error_code = ?
        WHERE id = ?
        """, OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC), errorCode, id);
  }

  /**
   * Abandons work that has exhausted its attempts.
   *
   * <p>Kept rather than deleted so an operator can see that a learner's resend never landed. The
   * row still carries no address, so what is retained is that a send failed, not who for.
   */
  void abandonVerificationResend(UUID id, String errorCode) {
    jdbc.update("""
        UPDATE identity.verification_resend_outbox
        SET status = 'TERMINAL', attempt_count = attempt_count + 1, last_error_code = ?
        WHERE id = ?
        """, errorCode, id);
  }

  /** One row of resend work. Carries a provider subject or null; never an address. */
  record VerificationResend(UUID id, String subject, int attemptCount) {
  }

  /**
   * Deletes counter rows whose window closed before the cutoff. Without it the table grows without
   * bound on the write path of a public endpoint.
   */
  int purgeAbuseCountersBefore(Instant cutoff) {
    return jdbc.update("DELETE FROM identity.abuse_counter WHERE window_started_at < ?",
        OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
  }

  // -------------------------------------------------------------------------------------------
  // Audit
  // -------------------------------------------------------------------------------------------

  /**
   * Appends one registration audit event. Only surrogates and codes: no email, mobile, code or
   * credential. The interaction id comes from MDC so a row joins to the request that produced it.
   */
  void audit(UUID operationId, UUID learnerId, UUID challengeId, String eventType, String outcome,
      String reasonCode) {
    jdbc.update("""
        INSERT INTO audit.registration_event(
          id, operation_id, learner_id, challenge_id, event_type, outcome, reason_code, interaction_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UuidV7.generate(), operationId, learnerId, challengeId, eventType, outcome, reasonCode,
        MDC.get("interactionId"));
  }

  static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the platform.", impossible);
    }
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  record Operation(UUID id, String fingerprint, String status, String subject) {
  }

  record RegistrationData(String firstName, String lastName, String email, String mobile,
      String country, String city, String termsVersion, String termsRef, String privacyVersion,
      String privacyRef, String adultVersion) {
  }

  record Contact(String email, String mobile, Instant emailVerifiedAt, Instant mobileVerifiedAt) {
  }

  record Challenge(UUID id, String mobile, byte[] otpHmac, String keyVersion, int attemptCount,
      int maxAttempts, Instant expiresAt, Instant consumedAt, Instant supersededAt,
      Instant verifiedAt) {
  }
}
