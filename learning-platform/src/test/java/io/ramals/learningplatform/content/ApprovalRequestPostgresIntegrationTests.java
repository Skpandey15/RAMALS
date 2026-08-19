package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.admin.AdminActivityRepository;
import io.ramals.learningplatform.observability.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL coverage for M1-T12's database arbiter and transaction boundary. */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class ApprovalRequestPostgresIntegrationTests {
  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";
  private static final UUID KAFKA_ASSESSMENT = UUID.fromString("01900000-0000-7000-8000-000000000401");
  private static final UUID KAFKA_CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");

  private static String databaseUrl;
  private JdbcTemplate migrationJdbc;
  private JdbcTemplate runtimeJdbc;
  private TransactionTemplate transaction;
  private ApprovalRequestService service;
  private UUID draftVersion;
  private UUID candidateId;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = required("RAMALS_TEST_POSTGRES_URL");
    String admin = required("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(databaseUrl, admin,
        required("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD")); Statement statement = connection.createStatement()) {
      String database = statement.enquoteIdentifier(currentDatabase(statement), true);
      statement.execute("""
          DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_migration') THEN
              CREATE ROLE ramals_core_migration LOGIN PASSWORD 'm0-t05-migration-test';
            ELSE ALTER ROLE ramals_core_migration WITH LOGIN PASSWORD 'm0-t05-migration-test'; END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_runtime') THEN
              CREATE ROLE ramals_core_runtime LOGIN PASSWORD 'm0-t05-runtime-test';
            ELSE ALTER ROLE ramals_core_runtime WITH LOGIN PASSWORD 'm0-t05-runtime-test'; END IF;
          END $$;
          """);
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + admin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + MIGRATION_USER + ", " + RUNTIME_USER);
    }
    Flyway.configure().dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration").defaultSchema("core").schemas("core", "ledger", "audit")
        .createSchemas(true).cleanDisabled(true).load().migrate();
  }

  @BeforeEach
  void setUp() {
    migrationJdbc = new JdbcTemplate(dataSource(MIGRATION_USER, MIGRATION_PASSWORD));
    var runtimeDataSource = dataSource(RUNTIME_USER, RUNTIME_PASSWORD);
    runtimeJdbc = new JdbcTemplate(runtimeDataSource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));
    ApprovalRequestRepository approvals = new ApprovalRequestRepository(runtimeJdbc, new ObjectMapper());
    AssessmentCandidateRevisionRepository candidates =
        new AssessmentCandidateRevisionRepository(runtimeJdbc, new ObjectMapper());
    service = new ApprovalRequestService(approvals, candidates,
        new AdminActivityRepository(runtimeJdbc, new DataSourceTransactionManager(runtimeDataSource)));
    draftVersion = UUID.randomUUID();
    candidateId = UuidV7.generate();
    migrationJdbc.update("""
        INSERT INTO core.assessment_version (id, assessment_id, curriculum_version_id, version_code, status)
        VALUES (?, ?, ?, ?, 'DRAFT')
        """, draftVersion, KAFKA_ASSESSMENT, KAFKA_CURRICULUM, "t12-" + draftVersion);
    migrationJdbc.update("""
        INSERT INTO core.assessment_candidate_revision
          (candidate_id, candidate_revision, source_proposal_id, assessment_version_id,
           item_code, skill_code, objective_code, item_type, difficulty, candidate_payload_jsonb,
           proposal_digest, trust_state, contract_version, agent_type, agent_version, model_route,
           model_id_unavailable_reason, prompt_version, interaction_id, created_by,
           idempotency_actor, idempotency_key, idempotency_fingerprint)
        VALUES (?, 1, ?, ?, 'AI_T12_CONCURRENT', 'KAFKA_TOPIC', 'TOPIC_DEFINE', 'SINGLE_CHOICE',
           'FOUNDATIONAL', ?::jsonb, ?, 'UNVERIFIED', '1.0', 'ASSESSMENT', 'v1', 'default',
           'test model identity unavailable', 'prompt-v1', 'interaction-t12', 'generator', 'generator', ?, ?)
        """, candidateId, "proposal-" + candidateId, draftVersion,
        "{\"answerKey\":[\"A\"],\"assessmentVersionId\":\"" + draftVersion
            + "\",\"difficulty\":\"FOUNDATIONAL\",\"itemCode\":\"AI_T12_CONCURRENT\","
            + "\"itemType\":\"SINGLE_CHOICE\",\"objectiveCode\":\"TOPIC_DEFINE\","
            + "\"options\":[\"A\",\"B\"],\"skillCode\":\"KAFKA_TOPIC\","
            + "\"stem\":\"What is a topic?\",\"rationale\":\"A topic is a named stream.\"}",
        "a".repeat(64), "intake-" + candidateId, "b".repeat(64));
  }

  @Test
  void concurrentCreateIsIdempotentAndConcurrentApproveCreatesExactlyOneItem() throws Exception {
    CountDownLatch createStart = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Outcome>> creates = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = "create-shared";
        creates.add(pool.submit(() -> {
          createStart.await(10, TimeUnit.SECONDS);
          try {
            ApprovalRequest request = transaction.execute(status ->
                service.create(candidateId, 1, "creator", key));
            return Outcome.success(request.state());
          } catch (RuntimeException failure) {
            return Outcome.failure(failure);
          }
        }));
      }
      createStart.countDown();
      List<Outcome> createResults = creates.stream().map(this::get).toList();
      assertThat(createResults).allMatch(result -> result.error() == null);
      assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM core.assessment_approval_request",
          Integer.class)).isEqualTo(1);
      assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM core.assessment_approval_command WHERE operation = 'CREATE'",
          Integer.class)).isEqualTo(1);

      UUID requestId = runtimeJdbc.queryForObject("SELECT id FROM core.assessment_approval_request", UUID.class);
      CountDownLatch approveStart = new CountDownLatch(1);
      List<Future<Outcome>> approvals = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = "approve-" + i;
        approvals.add(pool.submit(() -> {
          approveStart.await(10, TimeUnit.SECONDS);
          try {
            ApprovalRequest request = transaction.execute(status ->
                service.approve(requestId, "reviewer-" + key, key));
            return Outcome.success(request.state());
          } catch (RuntimeException failure) {
            return Outcome.failure(failure);
          }
        }));
      }
      approveStart.countDown();
      List<Outcome> approveResults = approvals.stream().map(this::get).toList();
      assertThat(approveResults.stream().filter(result -> result.state() == ApprovalState.APPROVED).count()).isEqualTo(1);
      assertThat(approveResults.stream().filter(result -> result.error() instanceof ApprovalRequestException exception
          && exception.code().equals("APPROVAL_STATE_CONFLICT")).count()).isEqualTo(1);
      assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM core.assessment_item_version WHERE item_code = 'AI_T12_CONCURRENT'",
          Integer.class)).isEqualTo(1);
      assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM core.assessment_approval_request WHERE state = 'APPROVED'",
          Integer.class)).isEqualTo(1);
    }
  }

  private Outcome get(Future<Outcome> result) {
    try { return result.get(30, TimeUnit.SECONDS); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }

  private DriverManagerDataSource dataSource(String user, String password) {
    DriverManagerDataSource source = new DriverManagerDataSource(databaseUrl, user, password);
    source.setDriverClassName("org.postgresql.Driver");
    return source;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
    return value;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (var result = statement.executeQuery("SELECT current_database()")) { result.next(); return result.getString(1); }
  }

  private record Outcome(ApprovalState state, RuntimeException error) {
    static Outcome success(ApprovalState state) { return new Outcome(state, null); }
    static Outcome failure(RuntimeException error) { return new Outcome(null, error); }
  }
}
