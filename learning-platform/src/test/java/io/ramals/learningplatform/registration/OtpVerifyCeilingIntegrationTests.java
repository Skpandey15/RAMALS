package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.observability.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The verify budget survives the rejection it causes.
 *
 * <p>The counter was charged inside the verification transaction, and only a wrong code was exempt
 * from rollback. Every other rejection - unknown, not-owned, expired, consumed, superseded,
 * exhausted - rolled the increment back with it, so an attacker probing challenge ids paid nothing
 * and never reached the ceiling. Only a real database shows this: with a mocked repository the
 * increment "happens" regardless of whether it would have committed.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class OtpVerifyCeilingIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  /** Mirrors MobileVerificationService.VERIFY_ATTEMPT_LIMIT. */
  private static final int VERIFY_LIMIT = 30;

  private static String databaseUrl;
  private static JdbcTemplate jdbc;
  private static RegistrationRepository registrations;
  private static LearnerRepository learners;
  private static OtpHmac otpHmac;
  private static RegistrationProperties properties;
  private static MobileVerificationService service;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void bootstrap() throws SQLException {
    databaseUrl = required("RAMALS_TEST_POSTGRES_URL");
    String adminUser = required("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, required("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      statement.execute("""
          DO $$
          BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_migration') THEN
              CREATE ROLE ramals_core_migration LOGIN PASSWORD 'm0-t05-migration-test';
            ELSE
              ALTER ROLE ramals_core_migration WITH LOGIN PASSWORD 'm0-t05-migration-test';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_runtime') THEN
              CREATE ROLE ramals_core_runtime LOGIN PASSWORD 'm0-t05-runtime-test';
            ELSE
              ALTER ROLE ramals_core_runtime WITH LOGIN PASSWORD 'm0-t05-runtime-test';
            END IF;
          END
          $$;
          """);
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO "
          + statement.enquoteIdentifier(adminUser, true));
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }
    Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  @BeforeEach
  void wire() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
    jdbc = new JdbcTemplate(dataSource);
    registrations = new RegistrationRepository(jdbc);
    learners = new LearnerRepository(jdbc);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactions = new TransactionTemplate(transactionManager);

    properties = new RegistrationProperties();
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 17);
    properties.getOtp().setHmacKeyRing("v1:" + Base64.getEncoder().encodeToString(key));
    otpHmac = new OtpHmac(properties);

    service = new MobileVerificationService(learners, registrations, properties, otpHmac,
        (mobile, otp) -> null, new AbuseCeiling(registrations, transactionManager),
        new SimpleMeterRegistry(), transactions);
  }

  private long counterRows(String subject) {
    Integer rows = jdbc.queryForObject(
        "SELECT coalesce(sum(request_count), 0) FROM identity.abuse_counter WHERE bucket_key = ?",
        Integer.class, RegistrationRepository.sha256("otp-verify:" + subject));
    return rows == null ? 0 : rows;
  }

  private UUID registeredLearner(String subject, String mobile) {
    Learner learner = learners.provisionForSubject(subject);
    UUID operationId = UuidV7.generate();
    jdbc.update("""
        INSERT INTO identity.registration_operation(id, idempotency_key, request_fingerprint, status)
        VALUES (?, ?, ?, 'STARTED')
        """, operationId, "key-" + subject, RegistrationRepository.sha256(subject));
    registrations.complete(operationId, learner.id(), new RegistrationRepository.RegistrationData(
        "Test", "Learner", subject + "@example.com", mobile, "IN", "Pune",
        "terms-v1", "terms/v1", "privacy-v1", "privacy/v1", "adult-18-v1"));
    registrations.markEmailVerified(learner.id());
    return learner.id();
  }

  /**
   * Calls verify inside a surrounding transaction, as the {@code @Transactional} proxy does in
   * production.
   *
   * <p>Calling the service directly would leave no outer transaction to roll back, so the rejection
   * would commit the counter either way and the test would pass against the very bug it exists to
   * catch. It did, until this was added.
   */
  private void verifyInTransaction(String subject, UUID challengeId, String otp) {
    // Mirrors @Transactional(noRollbackFor = InvalidOtpException.class): a wrong code commits its
    // attempt increment, everything else rolls back. Letting InvalidOtpException escape the template
    // would roll the increment back and misreport the attempt-durability case as a regression.
    InvalidOtpException wrongCode = transactions.execute(status -> {
      try {
        service.verify(subject, challengeId, otp);
        return null;
      } catch (InvalidOtpException rejected) {
        return rejected;
      }
    });
    if (wrongCode != null) {
      throw wrongCode;
    }
  }

  @Test
  @DisplayName("probing unknown challenge ids consumes the budget and eventually rate-limits")
  void unknownChallengeProbingIsCharged() {
    String subject = "ceiling-unknown-" + UUID.randomUUID();
    registeredLearner(subject, "+919000100001");

    // Each of these rejects with MOBILE_CHALLENGE_UNAVAILABLE and rolls its transaction back. Before
    // the fix the increment rolled back with it, so this loop could run forever.
    for (int attempt = 0; attempt < VERIFY_LIMIT; attempt++) {
      assertThatThrownBy(() -> verifyInTransaction(subject, UUID.randomUUID(), "123456"))
          .isInstanceOf(RegistrationException.class)
          .extracting(failure -> ((RegistrationException) failure).code())
          .isEqualTo("MOBILE_CHALLENGE_UNAVAILABLE");
    }

    assertThat(counterRows(subject)).isEqualTo(VERIFY_LIMIT);
    assertThatThrownBy(() -> verifyInTransaction(subject, UUID.randomUUID(), "123456"))
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo("MOBILE_OTP_RATE_LIMITED");
  }

  @Test
  @DisplayName("every unavailable-challenge rejection is charged, not just a wrong code")
  void eachRejectionShapeIsCharged() {
    String subject = "ceiling-shapes-" + UUID.randomUUID();
    UUID learnerId = registeredLearner(subject, "+919000100002");
    Instant now = Instant.now();

    UUID expired = challenge(learnerId, "+919000100002", now.minusSeconds(5));
    UUID consumed = challenge(learnerId, "+919000100002", now.plusSeconds(300));
    jdbc.update("UPDATE identity.mobile_verification_challenge SET consumed_at = CURRENT_TIMESTAMP"
        + " WHERE id = ?", consumed);
    UUID superseded = challenge(learnerId, "+919000100002", now.plusSeconds(300));
    jdbc.update("UPDATE identity.mobile_verification_challenge SET superseded_at = CURRENT_TIMESTAMP"
        + " WHERE id = ?", superseded);
    UUID exhausted = challenge(learnerId, "+919000100002", now.plusSeconds(300));
    jdbc.update("UPDATE identity.mobile_verification_challenge SET attempt_count = max_attempts"
        + " WHERE id = ?", exhausted);

    long before = counterRows(subject);
    for (UUID challengeId : new UUID[] {expired, consumed, superseded, exhausted}) {
      assertThatThrownBy(() -> verifyInTransaction(subject, challengeId, "123456"))
          .isInstanceOf(RegistrationException.class);
    }
    assertThat(counterRows(subject)).isEqualTo(before + 4);
  }

  @Test
  @DisplayName("a challenge owned by another learner is charged to the caller")
  void crossUserProbingIsCharged() {
    String owner = "ceiling-owner-" + UUID.randomUUID();
    String probe = "ceiling-probe-" + UUID.randomUUID();
    UUID ownerId = registeredLearner(owner, "+919000100003");
    registeredLearner(probe, "+919000100004");
    UUID challengeId = challenge(ownerId, "+919000100003", Instant.now().plusSeconds(300));

    long before = counterRows(probe);
    assertThatThrownBy(() -> verifyInTransaction(probe, challengeId, "123456"))
        .isInstanceOf(RegistrationException.class);
    assertThat(counterRows(probe)).isEqualTo(before + 1);
  }

  @Test
  @DisplayName("a wrong code still increments the challenge attempt count durably")
  void wrongCodeStillRecordsTheAttempt() {
    String subject = "ceiling-wrong-" + UUID.randomUUID();
    UUID learnerId = registeredLearner(subject, "+919000100005");
    UUID challengeId = challenge(learnerId, "+919000100005", Instant.now().plusSeconds(300));

    assertThatThrownBy(() -> verifyInTransaction(subject, challengeId, "654321"))
        .isInstanceOf(InvalidOtpException.class);

    // The noRollbackFor exemption is what makes this survive; the ceiling change must not have
    // displaced it.
    Integer attempts = jdbc.queryForObject(
        "SELECT attempt_count FROM identity.mobile_verification_challenge WHERE id = ?",
        Integer.class, challengeId);
    assertThat(attempts).isEqualTo(1);
    assertThat(counterRows(subject)).isEqualTo(1);
  }

  @Test
  @DisplayName("a successful verification commits its challenge and mobile reservation")
  void successStillCommits() {
    String subject = "ceiling-ok-" + UUID.randomUUID();
    UUID learnerId = registeredLearner(subject, "+919000100006");
    UUID challengeId = challenge(learnerId, "+919000100006", Instant.now().plusSeconds(300));

    transactions.execute(status -> service.verify(subject, challengeId, "123456"));

    RegistrationRepository.Contact contact = registrations.findContact(learnerId).orElseThrow();
    assertThat(contact.mobileVerifiedAt()).isNotNull();
    assertThat(registrations.findOnboardingState(learnerId)).contains("PROFILE_PENDING");
  }

  /** Inserts an open challenge whose code is always {@code 123456}. */
  private UUID challenge(UUID learnerId, String mobile, Instant expiresAt) {
    UUID challengeId = UuidV7.generate();
    registrations.insertChallenge(challengeId, learnerId, mobile,
        otpHmac.calculate("v1", challengeId, mobile, "123456"), "v1",
        properties.getOtp().getMaxAttempts(), properties.getOtp().getPolicyVersion(), expiresAt);
    return challengeId;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for the verify-ceiling suite.");
    }
    return value;
  }
}
