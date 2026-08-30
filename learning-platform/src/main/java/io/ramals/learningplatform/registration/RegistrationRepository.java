package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for registration operations, contact PII, onboarding state, mobile challenges, abuse
 * counters and the registration audit trail.
 *
 * <p><strong>Why one repository across five tables.</strong> They are a single consistency boundary,
 * not five: an onboarding state change is only meaningful together with the contact row it describes,
 * and a verified challenge is only meaningful together with the mobile reservation it produces.
 * Splitting them per table would let a caller update one without the other, which is the failure this
 * capability most needs to avoid.
 *
 * <p><strong>Plain JDBC, deliberately.</strong> The invariants here are database invariants — a
 * partial unique index, an upsert that must not overwrite, a {@code FOR UPDATE} that must actually
 * lock. Those read clearly as SQL and obscurely as mapped entities, and the mapping layer would add a
 * flush-ordering question to every one of them.
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
   * <p>The insert is {@code ON CONFLICT DO NOTHING} followed by a read, so two concurrent requests
   * carrying the same key converge on one row rather than racing to create two — the database
   * decides, not the application. The fingerprint is then compared in constant time: a key replayed
   * with a <em>different</em> body is a client defect or an attack, and answering it with the first
   * request's result would be worse than refusing it.
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
   * Records that an operation failed in a way a later attempt with the same key may resume from.
   *
   * <p>Without this the row stays at {@code STARTED} forever and the {@code FAILED_RECOVERABLE} state
   * declared in the V041 check constraint is unreachable — a state machine that exists in the schema
   * and nowhere in the behaviour. An operator triaging a stuck registration needs to see the
   * difference between "in flight" and "failed, retryable".
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
   * Writes the contact row and opens onboarding, without ever overwriting an existing one.
   *
   * <p>Both inserts are {@code ON CONFLICT DO NOTHING}. That is the second line of defence behind
   * {@code RegistrationService}'s refusal to persist for an identity it did not create: even if a
   * caller reached here for a learner who already has contact data, this cannot replace their name or
   * their mobile number with somebody else's.
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
   * <p>Separate from {@link #complete} on purpose. {@code EMAIL_PENDING} is what
   * {@code RegistrationService} treats as "this operation already finished, replay it as a no-op", so
   * it must not be reached until the verification mail has actually been requested. Setting it
   * alongside the contact insert would mean a send failure left the operation looking finished, and a
   * retry carrying the same Idempotency-Key would short-circuit — stranding a learner with an account
   * they were never told how to verify.
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
   * The learner's onboarding state, or empty for a learner who never registered.
   *
   * <p>Empty is a legitimate answer, not an error: ADR 0001 lets a learner exist from just-in-time
   * provisioning alone, and every such learner predates or bypasses this capability. Treating the
   * absence as a failure would make the legacy population unreadable.
   */
  Optional<String> findOnboardingState(UUID learnerId) {
    return jdbc.query(
        "SELECT onboarding_state FROM identity.professional_onboarding WHERE learner_id = ?",
        (row, index) -> row.getString(1), learnerId).stream().findFirst();
  }

  /**
   * Records trusted email verification and advances onboarding.
   *
   * <p>The state update is guarded by the states it may legally leave, so a learner who has already
   * progressed past mobile verification cannot be walked backwards by a late reconciliation.
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
   * <p>{@code expires_at} is converted to {@link OffsetDateTime} rather than passed as an
   * {@link Instant}. The PostgreSQL driver cannot infer a SQL type for {@code Instant} and throws
   * "Can't infer the SQL type to use" at execution — so an {@code Instant} here compiles, passes
   * every test with a mocked repository, and fails for the first learner who asks for a code.
   * {@link #withinCeiling} and {@link #purgeAbuseCountersBefore} convert for the same reason.
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
   * <p>The {@code learner_id} predicate is the cross-user control, and it belongs in the query rather
   * than in a check afterwards: a challenge id that belongs to somebody else must be indistinguishable
   * from one that does not exist, and an ownership test performed after a successful read is a test
   * somebody can later forget to perform.
   *
   * <p>{@code FOR UPDATE} serialises concurrent verification of the same challenge, so two racing
   * submissions of the same code cannot both consume it.
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
   * <p>The reservation is enforced by {@code uq_learner_contact_verified_mobile}, a partial unique
   * index over verified rows. Two learners verifying the same number concurrently both reach this
   * update; one commits and the other's constraint violation surfaces as
   * {@code MOBILE_ALREADY_REGISTERED}. A read-then-write check could not do this — both readers would
   * see the number free.
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
   * <p><strong>Why the database and not a local map.</strong> The service runs multiple replicas. An
   * in-process counter is bypassed by whichever pod the next request lands on, which for a public
   * registration route and an SMS budget is not a partial control but an absent one. A single upsert
   * returning the post-increment count makes the check atomic across replicas without a second
   * datastore.
   *
   * <p><strong>Why blocked requests still increment.</strong> The increment happens before the
   * comparison, so an attacker who keeps pushing keeps the window pinned above its ceiling rather
   * than sliding back under it. That deliberately makes sustained abuse cost the abuser the whole
   * window. The dimensions are hashed, so no bucket key holds an email, a mobile number or an
   * address.
   *
   * <p><strong>Why the window is aligned to the epoch.</strong> Every replica computes the same
   * boundary from the same clock arithmetic without coordinating. The trade is the usual fixed-window
   * one — up to twice the ceiling across a boundary — which is acceptable for an abuse ceiling whose
   * job is to bound cost, and not acceptable for anything asked to be exact.
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
   * Deletes counter rows whose window closed before the cutoff.
   *
   * <p>Called by {@link RegistrationAbuseCounterPurgeWorker}. Without it the table accumulates one
   * row per dimension per window forever — unbounded growth in the storage path of a public endpoint,
   * which is a slow denial of service against ourselves rather than a tidiness problem.
   */
  int purgeAbuseCountersBefore(Instant cutoff) {
    return jdbc.update("DELETE FROM identity.abuse_counter WHERE window_started_at < ?",
        OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
  }

  // -------------------------------------------------------------------------------------------
  // Audit
  // -------------------------------------------------------------------------------------------

  /**
   * Appends one registration audit event.
   *
   * <p>The table rejects UPDATE and DELETE by trigger, so this is the only way a row enters it and
   * there is no way for one to change afterwards. Only surrogates and codes are recorded: no email,
   * no mobile number, no code, no credential. The interaction id is taken from MDC so an audit row
   * joins to the request that produced it.
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
