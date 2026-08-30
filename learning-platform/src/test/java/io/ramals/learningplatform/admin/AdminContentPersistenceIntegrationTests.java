package io.ramals.learningplatform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises controlled content administration against real PostgreSQL: the seeded published version
 * is listed, retire transitions it to RETIRED while further transitions are rejected, publishing a
 * draft that fails the content-integrity rules is rejected, every operation and rejection is audited
 * with its interaction and trace ids, and the audit is immutable at both the privilege and trigger
 * layers.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
@TestMethodOrder(OrderAnnotation.class)
class AdminContentPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID KAFKA_DOMAIN = UUID.fromString("01900000-0000-7000-8000-000000000001");
  private static final UUID KAFKA_V1 = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final String ACTOR = "content-author-1";
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private static String databaseUrl;
  private ContentAdminService service;
  private AdminActivityRepository auditRepository;
  private JdbcTemplate runtimeJdbc;
  private JdbcTemplate migrationJdbc;

  @BeforeAll
  static void migrate() throws SQLException {
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

    org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private void wire() {
    if (service == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      migrationJdbc = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD));
      auditRepository = new AdminActivityRepository(runtimeJdbc,
          new JdbcTransactionManager(dataSource));
      service = new ContentAdminService(new ContentAdminRepository(runtimeJdbc), auditRepository);
    }
  }

  @Test
  @Order(1)
  void listsTheSeededPublishedVersion() {
    wire();
    assertThat(service.listCurricula())
        .anySatisfy(version -> {
          assertThat(version.domainCode()).isEqualTo("KAFKA");
          assertThat(version.versionCode()).isEqualTo("v1");
          assertThat(version.status()).isEqualTo("PUBLISHED");
        });
  }

  @Test
  @Order(2)
  void publishingADraftThatFailsIntegrityIsRejectedAndAudited() {
    wire();
    UUID draftId = UUID.fromString("01900000-0000-7000-8000-0000000009a1");
    runtimeJdbc.update("""
        INSERT INTO core.curriculum_version (id, domain_id, version_code)
        VALUES (?, ?, 'draft-empty')
        """, draftId, KAFKA_DOMAIN);

    String interactionId = "01920000-0000-7000-8000-000000000101";
    withCorrelation(interactionId, () ->
        assertThatThrownBy(() -> service.publishCurriculum(ACTOR, draftId.toString()))
            .isInstanceOf(ContentPublicationException.class));

    assertThat(auditRepository.findByInteractionId(interactionId))
        .anySatisfy(activity -> {
          assertThat(activity.action()).isEqualTo("PUBLISH_CURRICULUM");
          assertThat(activity.outcome()).isEqualTo("REJECTED");
          assertThat(activity.actorSubject()).isEqualTo(ACTOR);
          assertThat(activity.traceId()).isEqualTo(TRACE_ID);
        });
  }

  @Test
  @Order(3)
  void retireTransitionsThenFurtherTransitionsAreRejected() {
    wire();
    String interactionId = "01920000-0000-7000-8000-000000000102";
    withCorrelation(interactionId, () -> {
      CurriculumVersionSummary retired = service.retireCurriculum(ACTOR, KAFKA_V1.toString());
      assertThat(retired.status()).isEqualTo("RETIRED");

      // a retired version cannot be retired again or re-published
      assertThatThrownBy(() -> service.retireCurriculum(ACTOR, KAFKA_V1.toString()))
          .isInstanceOf(InvalidContentTransitionException.class);
      assertThatThrownBy(() -> service.publishCurriculum(ACTOR, KAFKA_V1.toString()))
          .isInstanceOf(InvalidContentTransitionException.class);
    });

    List<AdminActivity> audited = auditRepository.findByInteractionId(interactionId);
    assertThat(audited).extracting(AdminActivity::outcome).containsExactly("SUCCESS", "REJECTED", "REJECTED");
  }

  @Test
  @Order(4)
  void adminActivityAuditIsImmutable() {
    wire();
    UUID auditId = runtimeJdbc.queryForObject(
        "SELECT id FROM audit.admin_activity LIMIT 1", UUID.class);

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE audit.admin_activity SET outcome = 'SUCCESS' WHERE id = ?", auditId))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("42501"));
    assertThatThrownBy(() -> migrationJdbc.update(
        "DELETE FROM audit.admin_activity WHERE id = ?", auditId))
        .isInstanceOfSatisfying(DataAccessException.class,
            exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
  }

  @Test
  @Order(5)
  void legacyAuditSurvivesCallerRollback() {
    wire();
    String interactionId = "01920000-0000-7000-0000-000000000103";
    TransactionTemplate transaction = new TransactionTemplate(
        new JdbcTransactionManager(runtimeJdbc.getDataSource()));

    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      auditRepository.append(ACTOR, "LEGACY_REJECTION", "CURRICULUM_VERSION", KAFKA_V1,
          "REJECTED", "legacy rollback probe", interactionId, TRACE_ID);
      throw new IllegalStateException("rollback probe");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(auditRepository.findByInteractionId(interactionId))
        .singleElement()
        .satisfies(activity -> assertThat(activity.action()).isEqualTo("LEGACY_REJECTION"));
  }

  @Test
  @Order(6)
  void transactionalAuditRollsBackWithCaller() {
    wire();
    String interactionId = "01920000-0000-7000-0000-000000000104";
    TransactionTemplate transaction = new TransactionTemplate(
        new JdbcTransactionManager(runtimeJdbc.getDataSource()));

    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      auditRepository.appendWithinTransaction(ACTOR, "TRANSACTIONAL_PROBE",
          "CURRICULUM_VERSION", KAFKA_V1, "SUCCESS", "transaction rollback probe",
          interactionId, TRACE_ID);
      throw new IllegalStateException("rollback probe");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(auditRepository.findByInteractionId(interactionId)).isEmpty();
  }

  @Test
  @Order(7)
  void transactionalAuditCommitsWithCaller() {
    wire();
    String interactionId = "01920000-0000-7000-0000-000000000105";
    TransactionTemplate transaction = new TransactionTemplate(
        new JdbcTransactionManager(runtimeJdbc.getDataSource()));

    transaction.executeWithoutResult(status -> auditRepository.appendWithinTransaction(
        ACTOR, "TRANSACTIONAL_PROBE", "CURRICULUM_VERSION", KAFKA_V1, "SUCCESS",
        "transaction commit probe", interactionId, TRACE_ID));

    assertThat(auditRepository.findByInteractionId(interactionId))
        .singleElement()
        .satisfies(activity -> {
          assertThat(activity.action()).isEqualTo("TRANSACTIONAL_PROBE");
          assertThat(activity.outcome()).isEqualTo("SUCCESS");
        });
  }

  private void withCorrelation(String interactionId, Runnable action) {
    MDC.put("interactionId", interactionId);
    MDC.put("traceId", TRACE_ID);
    try {
      action.run();
    } finally {
      MDC.clear();
    }
  }

  private static String sqlState(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
    }
    return value;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery("SELECT current_database()")) {
      if (!result.next()) {
        throw new SQLException("PostgreSQL did not return current_database()");
      }
      return result.getString(1);
    }
  }
}
