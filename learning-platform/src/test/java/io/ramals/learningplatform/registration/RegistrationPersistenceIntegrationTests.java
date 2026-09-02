package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.observability.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The registration invariants that only real PostgreSQL can demonstrate.
 *
 * <p>Every assertion here is about something the database enforces and the application cannot: a
 * partial unique index resolving a race, an upsert refusing to overwrite, a trigger rejecting a
 * mutation, a {@code RETURNING} counter incrementing atomically across sessions. An in-memory
 * substitute would agree with all of them and prove none, because the guarantee under test is the
 * storage engine's rather than the code's.
 *
 * <p>Runs as the least-privileged runtime role, so the grants shipped in V041 are exercised too.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class RegistrationPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static String databaseUrl;
  private JdbcTemplate runtimeJdbc;
  private RegistrationRepository registrations;
  private LearnerRepository learners;

  @BeforeAll
  static void migrateAsRuntimeAndMigrationRoles() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    String adminUser = requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      String quotedAdmin = statement.enquoteIdentifier(adminUser, true);
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
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + quotedAdmin);
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

  private RegistrationRepository repository() {
    if (registrations == null) {
      runtimeJdbc = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD));
      registrations = new RegistrationRepository(runtimeJdbc);
      learners = new LearnerRepository(runtimeJdbc);
    }
    return registrations;
  }

  private UUID registeredLearner(String subject, String email, String mobile) {
    RegistrationRepository repository = repository();
    Learner learner = learners.provisionForSubject(subject);
    UUID operationId = UuidV7.generate();
    runtimeJdbc.update("""
        INSERT INTO identity.registration_operation(id, idempotency_key, request_fingerprint, status)
        VALUES (?, ?, ?, 'STARTED')
        """, operationId, "key-" + subject, RegistrationRepository.sha256(subject));
    repository.complete(operationId, learner.id(), new RegistrationRepository.RegistrationData(
        "Test", "Learner", email, mobile, "IN", "Pune", "terms-v1", "terms/v1", "privacy-v1",
        "privacy/v1", "adult-18-v1"));
    return learner.id();
  }

  // -------------------------------------------------------------------------------------------
  // core.learner stays PII-free
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("no contact column ever reaches core.learner")
  void coreLearnerHasNoContactColumns() {
    repository();
    List<String> columns = runtimeJdbc.queryForList("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'core' AND table_name = 'learner'
        """, String.class);
    assertThat(columns).doesNotContain("first_name", "last_name", "email", "email_normalized",
        "mobile", "mobile_e164", "city", "country_code", "date_of_birth");
  }

  // -------------------------------------------------------------------------------------------
  // Mobile uniqueness: the race the application cannot win alone
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("two learners cannot both verify the same mobile number")
  void verifiedMobileIsUniqueAcrossLearners() {
    RegistrationRepository repository = repository();
    UUID first = registeredLearner("uniq-a", "uniq-a@example.com", "+919000000001");
    UUID second = registeredLearner("uniq-b", "uniq-b@example.com", "+919000000002");

    UUID firstChallenge = openChallenge(first, "+919000000009");
    repository.recordVerifiedMobile(firstChallenge, first, "+919000000009");

    UUID secondChallenge = openChallenge(second, "+919000000009");
    assertThatThrownBy(() -> repository.recordVerifiedMobile(secondChallenge, second,
        "+919000000009"))
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo("MOBILE_ALREADY_REGISTERED");
  }

  @Test
  @DisplayName("concurrent claims on one number produce exactly one verified owner")
  void concurrentMobileClaimsProduceOneWinner() throws Exception {
    RegistrationRepository repository = repository();
    String contested = "+919000000077";
    int contenders = 6;
    List<UUID> learnerIds = new ArrayList<>();
    List<UUID> challengeIds = new ArrayList<>();
    for (int index = 0; index < contenders; index++) {
      UUID learnerId = registeredLearner("race-" + index, "race-" + index + "@example.com",
          "+9190000010" + index);
      learnerIds.add(learnerId);
      challengeIds.add(openChallenge(learnerId, contested));
    }

    // A read-then-write check cannot resolve this: every contender would see the number free.
    // The partial unique index is what makes exactly one of them win.
    CyclicBarrier barrier = new CyclicBarrier(contenders);
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    try {
      List<Future<Boolean>> outcomes = new ArrayList<>();
      for (int index = 0; index < contenders; index++) {
        UUID learnerId = learnerIds.get(index);
        UUID challengeId = challengeIds.get(index);
        Callable<Boolean> claim = () -> {
          barrier.await();
          try {
            new RegistrationRepository(new JdbcTemplate(new DriverManagerDataSource(
                databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD)))
                .recordVerifiedMobile(challengeId, learnerId, contested);
            return true;
          } catch (RuntimeException rejected) {
            return false;
          }
        };
        outcomes.add(pool.submit(claim));
      }
      long winners = 0;
      for (Future<Boolean> outcome : outcomes) {
        if (outcome.get()) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    Integer verifiedOwners = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM identity.learner_contact WHERE mobile_e164 = ? "
            + "AND mobile_verified_at IS NOT NULL", Integer.class, contested);
    assertThat(verifiedOwners).isEqualTo(1);
  }

  @Test
  @DisplayName("an unverified duplicate number is allowed; only verification reserves it")
  void reservationAppliesOnlyToVerifiedNumbers() {
    repository();
    // Two learners may register the same number; the index is partial, so the conflict arises at
    // verification. That is deliberate: registration must not become a way to probe which numbers
    // are already taken.
    registeredLearner("dup-a", "dup-a@example.com", "+919000000055");
    registeredLearner("dup-b", "dup-b@example.com", "+919000000055");
    Integer rows = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM identity.learner_contact WHERE mobile_e164 = ?", Integer.class,
        "+919000000055");
    assertThat(rows).isEqualTo(2);
  }

  // -------------------------------------------------------------------------------------------
  // Idempotency and duplicate registration
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("one Idempotency-Key yields one operation, however many times it is claimed")
  void repeatedClaimsOfOneKeyYieldOneOperation() {
    RegistrationRepository repository = repository();
    String fingerprint = RegistrationRepository.sha256("stable-body");
    RegistrationRepository.Operation first = repository.start("idem-1", fingerprint);
    RegistrationRepository.Operation second = repository.start("idem-1", fingerprint);

    assertThat(second.id()).isEqualTo(first.id());
    Integer rows = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM identity.registration_operation WHERE idempotency_key = ?",
        Integer.class, "idem-1");
    assertThat(rows).isEqualTo(1);
  }

  @Test
  @DisplayName("a key replayed with a different body is refused")
  void keyReplayedWithADifferentBodyIsRefused() {
    RegistrationRepository repository = repository();
    repository.start("idem-2", RegistrationRepository.sha256("body-one"));
    assertThatThrownBy(() -> repository.start("idem-2", RegistrationRepository.sha256("body-two")))
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo("REGISTRATION_IDEMPOTENCY_KEY_CONFLICT");
  }

  @Test
  @DisplayName("concurrent claims of one key converge on a single operation row")
  void concurrentClaimsOfOneKeyConverge() throws Exception {
    repository();
    String fingerprint = RegistrationRepository.sha256("concurrent-body");
    int contenders = 6;
    CyclicBarrier barrier = new CyclicBarrier(contenders);
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    try {
      List<Future<UUID>> results = new ArrayList<>();
      for (int index = 0; index < contenders; index++) {
        results.add(pool.submit(() -> {
          barrier.await();
          return new RegistrationRepository(new JdbcTemplate(new DriverManagerDataSource(
              databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD)))
              .start("idem-concurrent", fingerprint).id();
        }));
      }
      List<UUID> ids = new ArrayList<>();
      for (Future<UUID> result : results) {
        ids.add(result.get());
      }
      assertThat(ids).doesNotContainNull().containsOnly(ids.getFirst());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("completing twice never overwrites an existing contact row")
  void completeNeverOverwritesExistingContactData() {
    RegistrationRepository repository = repository();
    UUID learnerId = registeredLearner("overwrite", "original@example.com", "+919000000031");

    UUID secondOperation = UuidV7.generate();
    runtimeJdbc.update("""
        INSERT INTO identity.registration_operation(id, idempotency_key, request_fingerprint, status)
        VALUES (?, ?, ?, 'STARTED')
        """, secondOperation, "key-overwrite-2", RegistrationRepository.sha256("second"));
    repository.complete(secondOperation, learnerId, new RegistrationRepository.RegistrationData(
        "Attacker", "Name", "attacker@example.com", "+919999999999", "IN", "Delhi",
        "terms-v1", "terms/v1", "privacy-v1", "privacy/v1", "adult-18-v1"));

    // The second line of defence behind the service's refusal to persist for an identity it did not
    // create. Even reaching here must not replace a learner's name or number with somebody else's.
    RegistrationRepository.Contact contact = repository.findContact(learnerId).orElseThrow();
    assertThat(contact.email()).isEqualTo("original@example.com");
    assertThat(contact.mobile()).isEqualTo("+919000000031");
  }

  // -------------------------------------------------------------------------------------------
  // Challenge ownership and state
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a challenge is invisible to any learner but its owner")
  void challengeIsScopedToItsOwner() {
    RegistrationRepository repository = repository();
    UUID owner = registeredLearner("chal-owner", "chal-owner@example.com", "+919000000041");
    UUID other = registeredLearner("chal-other", "chal-other@example.com", "+919000000042");
    UUID challengeId = openChallenge(owner, "+919000000041");

    assertThat(repository.lockChallengeForVerification(challengeId, owner)).isPresent();
    // Absent, not present-and-rejected: an ownership test performed after a successful read is one
    // somebody can later forget to perform.
    assertThat(repository.lockChallengeForVerification(challengeId, other)).isEmpty();
  }

  @Test
  @DisplayName("the attempt ceiling is enforced by a check constraint, not only by code")
  void attemptCeilingIsAConstraint() {
    RegistrationRepository repository = repository();
    UUID learnerId = registeredLearner("attempts", "attempts@example.com", "+919000000051");
    UUID challengeId = openChallenge(learnerId, "+919000000051");

    for (int attempt = 0; attempt < 5; attempt++) {
      repository.recordFailedAttempt(challengeId);
    }
    // The guarded update stops incrementing at the ceiling rather than violating the constraint.
    repository.recordFailedAttempt(challengeId);
    Integer attempts = runtimeJdbc.queryForObject(
        "SELECT attempt_count FROM identity.mobile_verification_challenge WHERE id = ?",
        Integer.class, challengeId);
    assertThat(attempts).isEqualTo(5);
  }

  @Test
  @DisplayName("issuing a challenge supersedes the previous one")
  void issuingAChallengeSupersedesThePrevious() {
    RegistrationRepository repository = repository();
    UUID learnerId = registeredLearner("supersede", "supersede@example.com", "+919000000061");
    UUID first = openChallenge(learnerId, "+919000000061");
    repository.supersedeOpenChallenges(learnerId);
    UUID second = openChallenge(learnerId, "+919000000061");

    assertThat(repository.lockChallengeForVerification(first, learnerId).orElseThrow()
        .supersededAt()).isNotNull();
    assertThat(repository.lockChallengeForVerification(second, learnerId).orElseThrow()
        .supersededAt()).isNull();
  }

  // -------------------------------------------------------------------------------------------
  // Abuse counters
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the ceiling is shared across sessions, so another replica cannot reset it")
  void ceilingIsSharedAcrossConnections() {
    repository();
    String dimension = "shared-" + UUID.randomUUID();
    // A second repository on its own connection stands in for a second replica. An in-process
    // counter would give this one a fresh allowance, which is what §10 forbids.
    RegistrationRepository otherReplica = new RegistrationRepository(new JdbcTemplate(
        new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD)));

    assertThat(registrations.withinCeiling(dimension, 2, 3600)).isTrue();
    assertThat(otherReplica.withinCeiling(dimension, 2, 3600)).isTrue();
    assertThat(otherReplica.withinCeiling(dimension, 2, 3600)).isFalse();
    assertThat(registrations.withinCeiling(dimension, 2, 3600)).isFalse();
  }

  @Test
  @DisplayName("counter keys are hashed, so no bucket holds an address or a number")
  void counterKeysAreHashed() {
    RegistrationRepository repository = repository();
    repository.withinCeiling("sms-mobile:+919000000099", 5, 3600);
    List<String> keys = runtimeJdbc.queryForList(
        "SELECT bucket_key FROM identity.abuse_counter", String.class);
    assertThat(keys).isNotEmpty();
    assertThat(keys).allSatisfy(key -> assertThat(key).matches("[0-9a-f]{64}"));
    assertThat(keys).noneMatch(key -> key.contains("+9190000"));
  }

  @Test
  @DisplayName("the purge removes closed windows and leaves live ones")
  void purgeRemovesOnlyClosedWindows() {
    RegistrationRepository repository = repository();
    repository.withinCeiling("purge-live-" + UUID.randomUUID(), 5, 3600);
    runtimeJdbc.update("""
        INSERT INTO identity.abuse_counter(bucket_key, window_started_at, request_count)
        VALUES (?, ?, 1)
        """, RegistrationRepository.sha256("purge-stale"),
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(30));

    int deleted = repository.purgeAbuseCountersBefore(Instant.now().minusSeconds(7 * 86400));

    assertThat(deleted).isGreaterThanOrEqualTo(1);
    Integer stale = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM identity.abuse_counter WHERE bucket_key = ?", Integer.class,
        RegistrationRepository.sha256("purge-stale"));
    assertThat(stale).isZero();
    // Without the sweep the table grows forever on the write path of a public endpoint, until the
    // insert that enforces the rate limit is the slowest thing in the request.
    Integer live = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM identity.abuse_counter", Integer.class);
    assertThat(live).isPositive();
  }

  // -------------------------------------------------------------------------------------------
  // Audit
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the registration audit trail cannot be updated or deleted, even by the runtime role")
  void registrationAuditIsAppendOnly() {
    RegistrationRepository repository = repository();
    UUID learnerId = registeredLearner("audit", "audit@example.com", "+919000000071");
    repository.audit(null, learnerId, null, "MOBILE_VERIFIED", "SUCCESS", null);

    // Two independent layers stop a mutation, and the grant is the one that fires first: the runtime
    // role is never granted UPDATE or DELETE on an audit table (V002 default privileges), so the
    // statement is refused before the trigger is reached. Asserting only the trigger's wording would
    // therefore fail here -- and would also hide the fact that the stronger control is the grant.
    // Asserted on the root cause: Spring translates the driver error into BadSqlGrammarException,
    // whose own message carries only the statement, so a top-level match would pass for any SQL
    // failure at all -- including one where the update was rejected for the wrong reason.
    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE audit.registration_event SET outcome = 'FAILURE' WHERE learner_id = ?", learnerId))
        .rootCause().hasMessageContaining("permission denied");
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM audit.registration_event WHERE learner_id = ?", learnerId))
        .rootCause().hasMessageContaining("permission denied");
    assertThat(runtimeJdbc.queryForObject(
        "SELECT count(*) FROM audit.registration_event WHERE learner_id = ?", Integer.class,
        learnerId)).isPositive();

    // The trigger is the second layer, and it must exist so that a role which does hold UPDATE --
    // the migration role, or a future operator account -- is still refused.
    assertThat(runtimeJdbc.queryForObject("""
        SELECT count(*) FROM pg_trigger
        WHERE tgrelid = 'audit.registration_event'::regclass AND NOT tgisinternal
        """, Integer.class)).isPositive();
  }

  @Test
  @DisplayName("the append-only trigger refuses a mutation by a role that does hold UPDATE")
  void appendOnlyTriggerRefusesAPrivilegedMutation() throws SQLException {
    RegistrationRepository repository = repository();
    UUID learnerId = registeredLearner("audit-trigger", "audit-trigger@example.com",
        "+919000000091");
    repository.audit(null, learnerId, null, "MOBILE_VERIFIED", "SUCCESS", null);

    // The migration role owns the table and is not blocked by the grant, so this is the only way to
    // demonstrate that the trigger itself works rather than being shadowed by the privilege check.
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute(
          "UPDATE audit.registration_event SET outcome = 'FAILURE' WHERE learner_id = '"
              + learnerId + "'"))
          .hasMessageContaining("append-only");
    }
  }

  @Test
  @DisplayName("audit rows carry surrogates and codes, never contact data or a code")
  void auditRowsCarryNoSensitiveValues() {
    RegistrationRepository repository = repository();
    UUID learnerId = registeredLearner("audit-2", "secret-address@example.com", "+919000000081");
    repository.audit(null, learnerId, null, "MOBILE_OTP_SENT", "SUCCESS", null);

    List<String> reasons = runtimeJdbc.queryForList(
        "SELECT coalesce(reason_code, '') FROM audit.registration_event WHERE learner_id = ?",
        String.class, learnerId);
    assertThat(reasons).noneMatch(reason -> reason.contains("@") || reason.contains("+91"));
    List<String> columns = runtimeJdbc.queryForList("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'audit' AND table_name = 'registration_event'
        """, String.class);
    assertThat(columns).doesNotContain("email", "mobile", "otp", "password", "payload");
  }

  private UUID openChallenge(UUID learnerId, String mobile) {
    UUID challengeId = UuidV7.generate();
    repository().insertChallenge(challengeId, learnerId, mobile, new byte[] {1, 2, 3}, "v1", 5,
        "otp-v1", Instant.now().plusSeconds(300));
    return challengeId;
  }

  // -------------------------------------------------------------------------------------------
  // Per-learner isolation of the onboarding artefacts
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("one learner's profile, journey and goal are invisible to another learner")
  void onboardingArtefactsAreScopedToTheirOwnLearner() {
    repository();
    ProfessionalProfileRepository profiles = new ProfessionalProfileRepository(runtimeJdbc);
    LearningJourneyRepository journeys = new LearningJourneyRepository(runtimeJdbc);

    UUID owner = registeredLearner("scope-a", "scope-a@example.com", "+919000001001");
    UUID other = registeredLearner("scope-b", "scope-b@example.com", "+919000001002");
    UUID domainId = learners.findActiveDomainId("KAFKA").orElseThrow();

    profiles.save(owner, new ProfessionalProfileRequest(
        "Staff Engineer", "FIVE_TO_TEN_YEARS", "Distributed systems", "ADVANCED"));
    journeys.save(owner, domainId, new LearningJourneyRequest(
        "ROLE_TRANSITION", "Principal Engineer", "STEADY", 8, "KAFKA",
        new java.math.BigDecimal("0.800"), null));
    learners.upsertGoal(owner, domainId, new java.math.BigDecimal("0.800"), null);

    // The queries filter on learner_id, so the second learner reads absence rather than someone
    // else's answers. There is no endpoint that accepts a learner id, but the storage layer is where
    // that guarantee has to be true -- a future caller that resolves the wrong id must find nothing,
    // not another learner's professional history.
    assertThat(profiles.find(other)).isEmpty();
    assertThat(journeys.find(other)).isEmpty();
    assertThat(learners.findGoal(other)).isEmpty();

    assertThat(profiles.find(owner)).isPresent();
    assertThat(journeys.find(owner)).isPresent();

    // And the second learner writing their own does not disturb the first's.
    profiles.save(other, new ProfessionalProfileRequest(
        "Analyst", "ONE_TO_THREE_YEARS", "Reporting", null));
    assertThat(profiles.find(owner).orElseThrow().currentRole()).isEqualTo("Staff Engineer");
    assertThat(profiles.find(other).orElseThrow().currentRole()).isEqualTo("Analyst");
  }

  @Test
  @DisplayName("the onboarding transitions move only the learner they name")
  void transitionsMoveOnlyTheirOwnLearner() {
    RegistrationRepository repository = repository();
    ProfessionalProfileRepository profiles = new ProfessionalProfileRepository(runtimeJdbc);
    LearningJourneyRepository journeys = new LearningJourneyRepository(runtimeJdbc);

    UUID moving = registeredLearner("move-a", "move-a@example.com", "+919000002001");
    UUID staying = registeredLearner("move-b", "move-b@example.com", "+919000002002");

    // Both start at EMAIL_PENDING. The guarded UPDATEs must refuse them there and, once one learner
    // is walked forward, must leave the other exactly where it found them.
    assertThat(profiles.advanceToJourneyPending(moving)).isZero();
    assertThat(journeys.advanceToOnboarded(moving)).isZero();

    runtimeJdbc.update("UPDATE identity.professional_onboarding SET onboarding_state = "
        + "'PROFILE_PENDING' WHERE learner_id = ?", moving);
    assertThat(profiles.advanceToJourneyPending(moving)).isEqualTo(1);
    assertThat(journeys.advanceToOnboarded(moving)).isEqualTo(1);

    assertThat(repository.findOnboardingState(moving)).contains("ONBOARDED");
    assertThat(repository.findOnboardingState(staying)).contains("EMAIL_PENDING");

    // Repeating either transition is a no-op rather than an error, which is what makes a
    // double-submitted form idempotent at the level that actually decides.
    assertThat(profiles.advanceToJourneyPending(moving)).isZero();
    assertThat(journeys.advanceToOnboarded(moving)).isZero();
    assertThat(repository.findOnboardingState(moving)).contains("ONBOARDED");
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for PostgreSQL integration tests.");
    }
    return value;
  }
}
