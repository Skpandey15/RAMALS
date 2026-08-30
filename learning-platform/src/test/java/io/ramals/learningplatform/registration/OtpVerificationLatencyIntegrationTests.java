package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OTP verification latency against the documented budget: p95 <= 250 ms, p99 <= 500 ms.
 *
 * <p>Measures the authoritative path only — subject resolution, the locking read, the keyed-HMAC
 * comparison, the mobile reservation and the onboarding transition — against real PostgreSQL inside a
 * real transaction. No provider call is on this path, so nothing external is being excluded by
 * sleight of hand; the SMS gateway is only involved in {@code send}.
 *
 * <p>Opt-in rather than part of {@code check}: a shared CI runner's scheduling noise makes a latency
 * assertion flaky, and a flaky performance gate gets muted, which is worse than an explicit one. Run
 * it deliberately on a quiet machine:
 *
 * <pre>
 *   RAMALS_TEST_POSTGRES_URL=jdbc:postgresql://localhost:55432/ramals \
 *   RAMALS_TEST_POSTGRES_ADMIN_USER=... RAMALS_TEST_POSTGRES_ADMIN_PASSWORD=... \
 *   RAMALS_TEST_POSTGRES_ALLOW_RESET=true RAMALS_TEST_OTP_LATENCY=true \
 *   ./gradlew :learning-platform:integrationTest --tests '*OtpVerificationLatency*'
 * </pre>
 *
 * <p>The percentiles are printed, so a run that passes still reports the headroom it passed with.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_OTP_LATENCY", matches = "(?i)true")
class OtpVerificationLatencyIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final int WARMUP = 30;
  private static final int SAMPLES = 200;
  private static final long P95_BUDGET_MS = 250;
  private static final long P99_BUDGET_MS = 500;

  private static String databaseUrl;
  private static JdbcTemplate jdbc;
  private static RegistrationRepository registrations;
  private static LearnerRepository learners;
  private static OtpHmac otpHmac;
  private static MobileVerificationService service;
  private static TransactionTemplate transactions;
  private static RegistrationProperties properties;

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

    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
    jdbc = new JdbcTemplate(dataSource);
    registrations = new RegistrationRepository(jdbc);
    learners = new LearnerRepository(jdbc);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactions = new TransactionTemplate(transactionManager);

    properties = new RegistrationProperties();
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 13);
    properties.getOtp().setHmacKeyRing("v1:" + Base64.getEncoder().encodeToString(key));
    otpHmac = new OtpHmac(properties);

    service = new MobileVerificationService(learners, registrations, properties, otpHmac,
        (mobile, otp) -> null, new AbuseCeiling(registrations, transactionManager),
        new SimpleMeterRegistry(), transactions);
  }

  @Test
  @DisplayName("OTP verification meets the p95 and p99 budget on the authoritative path")
  void otpVerificationMeetsItsLatencyBudget() {
    List<Long> samples = new ArrayList<>(SAMPLES);

    for (int iteration = 0; iteration < WARMUP + SAMPLES; iteration++) {
      Fixture fixture = freshChallenge(iteration);
      // The transaction template stands in for the @Transactional proxy, so the measured path
      // carries the same locking and commit cost it has in production.
      long startedAt = System.nanoTime();
      transactions.execute(status -> service.verify(fixture.subject(), fixture.challengeId(),
          fixture.otp()));
      long elapsedNanos = System.nanoTime() - startedAt;

      if (iteration >= WARMUP) {
        samples.add(elapsedNanos / 1_000_000L);
      }
    }

    samples.sort(Long::compareTo);
    long p50 = percentile(samples, 50);
    long p95 = percentile(samples, 95);
    long p99 = percentile(samples, 99);
    System.out.printf("OTP verify latency over %d samples: p50=%dms p95=%dms p99=%dms max=%dms%n",
        samples.size(), p50, p95, p99, samples.getLast());

    assertThat(p95).as("p95 must stay within %dms", P95_BUDGET_MS).isLessThanOrEqualTo(P95_BUDGET_MS);
    assertThat(p99).as("p99 must stay within %dms", P99_BUDGET_MS).isLessThanOrEqualTo(P99_BUDGET_MS);
  }

  private static long percentile(List<Long> sorted, int percentile) {
    int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  /** A learner with a verified email and one open challenge, created outside the timed section. */
  private static Fixture freshChallenge(int iteration) {
    String subject = "latency-" + iteration + "-" + UUID.randomUUID();
    Learner learner = learners.provisionForSubject(subject);
    String mobile = "+9190000%05d".formatted(iteration);

    UUID operationId = UuidV7.generate();
    jdbc.update("""
        INSERT INTO identity.registration_operation(id, idempotency_key, request_fingerprint, status)
        VALUES (?, ?, ?, 'STARTED')
        """, operationId, "latency-" + subject, RegistrationRepository.sha256(subject));
    registrations.complete(operationId, learner.id(), new RegistrationRepository.RegistrationData(
        "Perf", "Learner", subject + "@example.com", mobile, "IN", "Pune",
        "terms-v1", "terms/v1", "privacy-v1", "privacy/v1", "adult-18-v1"));
    registrations.markEmailVerified(learner.id());

    UUID challengeId = UuidV7.generate();
    String otp = "%06d".formatted(iteration % 1_000_000);
    registrations.insertChallenge(challengeId, learner.id(), mobile,
        otpHmac.calculate("v1", challengeId, mobile, otp), "v1",
        properties.getOtp().getMaxAttempts(), properties.getOtp().getPolicyVersion(),
        Instant.now().plusSeconds(300));
    return new Fixture(subject, challengeId, otp);
  }

  private record Fixture(String subject, UUID challengeId, String otp) {
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
      throw new IllegalStateException(name + " must be set for the latency suite.");
    }
    return value;
  }
}
