package io.ramals.learningplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the learning session lifecycle against real PostgreSQL: start resumes an open session
 * and preserves its checkpoint, a stale version is rejected by the optimistic guard, invalid
 * transitions are refused, each transition is logged with its interactionId, and a terminal session
 * does not block a fresh one.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class LearningSessionPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";
  private static final String INTERACTION_ID = "01920000-0000-7000-8000-0000000000e1";

  private static String databaseUrl;
  private LearningSessionService sessionService;
  private JdbcTemplate runtimeJdbc;
  private JsonMapper mapper;

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
    if (sessionService == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      mapper = JsonMapper.builder().build();
      LearnerService learnerService = new LearnerService(new LearnerRepository(runtimeJdbc));
      CurriculumService curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      sessionService = new LearningSessionService(
          new LearningSessionRepository(runtimeJdbc, mapper), new LearningSessionPolicy(),
          learnerService, curriculumService);
    }
    MDC.put("interactionId", INTERACTION_ID);
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private SessionTransitionRequest transition(
      LearningSessionCommand command, int expectedVersion, String checkpointJson) {
    JsonNode checkpoint = checkpointJson == null ? null : mapper.readTree(checkpointJson);
    return new SessionTransitionRequest(command, expectedVersion, checkpoint);
  }

  @Test
  void startResumesOpenSessionAndPreservesCheckpoint() {
    wire();
    LearningSession created = sessionService.start("sess-resume", "KAFKA", "v1").session();
    sessionService.transition("sess-resume", created.id().toString(),
        transition(LearningSessionCommand.PAUSE, 1, "{\"focusSkill\":\"KAFKA_TOPIC\"}"));

    SessionStartResult resumed = sessionService.start("sess-resume", "KAFKA", "v1");
    assertThat(resumed.created()).isFalse();
    assertThat(resumed.session().id()).isEqualTo(created.id());
    assertThat(resumed.session().status()).isEqualTo(LearningSessionStatus.PAUSED);
    assertThat(resumed.session().checkpoint().toString()).contains("KAFKA_TOPIC");
  }

  @Test
  void staleVersionTransitionIsRejected() {
    wire();
    LearningSession session = sessionService.start("sess-stale", "KAFKA", "v1").session();
    sessionService.transition("sess-stale", session.id().toString(),
        transition(LearningSessionCommand.PAUSE, 1, null));

    // the session is now at version 2; a command citing version 1 has lost the race
    assertThatThrownBy(() -> sessionService.transition("sess-stale", session.id().toString(),
        transition(LearningSessionCommand.RESUME, 1, null)))
        .isInstanceOf(SessionConflictException.class);
  }

  @Test
  void invalidTransitionIsRejected() {
    wire();
    LearningSession session = sessionService.start("sess-invalid", "KAFKA", "v1").session();

    // RESUME is not valid from ACTIVE
    assertThatThrownBy(() -> sessionService.transition("sess-invalid", session.id().toString(),
        transition(LearningSessionCommand.RESUME, 1, null)))
        .isInstanceOf(InvalidSessionTransitionException.class);
  }

  @Test
  void everyTransitionIsLoggedWithItsInteractionId() {
    wire();
    LearningSession session = sessionService.start("sess-log", "KAFKA", "v1").session();
    sessionService.transition("sess-log", session.id().toString(),
        transition(LearningSessionCommand.PAUSE, 1, null));

    Long logged = runtimeJdbc.queryForObject("""
        SELECT count(*) FROM core.learning_session_transition
        WHERE session_id = ? AND interaction_id = ?
        """, Long.class, session.id(), INTERACTION_ID);
    assertThat(logged).isEqualTo(2);  // START and PAUSE
  }

  @Test
  void terminalSessionDoesNotBlockANewOne() {
    wire();
    LearningSession first = sessionService.start("sess-terminal", "KAFKA", "v1").session();
    sessionService.transition("sess-terminal", first.id().toString(),
        transition(LearningSessionCommand.COMPLETE, 1, null));

    SessionStartResult second = sessionService.start("sess-terminal", "KAFKA", "v1");
    assertThat(second.created()).isTrue();
    assertThat(second.session().id()).isNotEqualTo(first.id());
    assertThat(second.session().status()).isEqualTo(LearningSessionStatus.ACTIVE);
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
