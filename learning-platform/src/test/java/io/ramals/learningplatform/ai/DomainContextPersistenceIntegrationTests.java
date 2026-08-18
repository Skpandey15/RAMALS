package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import io.ramals.learningplatform.ai.contract.GoalType;
import io.ramals.learningplatform.ai.contract.LearningGoalContext;
import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.LearnerGoal;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The domain context must be read from the database, not decided in Java.
 *
 * <p>This is the assertion that makes {@code domainContext} more than a reserved field. Against
 * real PostgreSQL, a skill resolves to the domain that actually owns it, with the type and
 * published curriculum version the platform actually records. A test with a stubbed repository
 * would pass just as happily against a hardcoded {@code TECHNOLOGY}, which is the outcome worth
 * ruling out.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DomainContextPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static String databaseUrl;
  private DomainContextAssembler assembler;
  private JdbcTemplate migrationJdbc;

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
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }

    Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for this integration test");
    }
    return value;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (var result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  @BeforeEach
  void setUp() {
    JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDataSource());
    assembler = new DomainContextAssembler(
        new CurriculumService(new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator()));
    migrationJdbc = new JdbcTemplate(migrationDataSource());
  }

  private DriverManagerDataSource runtimeDataSource() {
    return dataSource(RUNTIME_USER, RUNTIME_PASSWORD);
  }

  private DriverManagerDataSource migrationDataSource() {
    return dataSource(MIGRATION_USER, MIGRATION_PASSWORD);
  }

  private DriverManagerDataSource dataSource(String user, String password) {
    DriverManagerDataSource source = new DriverManagerDataSource(databaseUrl, user, password);
    source.setDriverClassName("org.postgresql.Driver");
    return source;
  }

  @Test
  @DisplayName("a seeded skill resolves to the domain that owns it")
  void skillResolvesToItsOwningDomain() {
    Optional<DomainContext> context = assembler.forSkill("KAFKA_TOPIC");

    assertThat(context).isPresent();
    assertThat(context.get().domainCode()).isEqualTo("KAFKA");
    assertThat(context.get().domainType()).isEqualTo(DomainType.TECHNOLOGY);
  }

  @Test
  @DisplayName("the domain type comes from the column, not from application code")
  void domainTypeIsReadFromTheDatabase() {
    // Flip the stored value and the context must follow. If it does not, the assembler is deciding
    // the type rather than reporting it -- the exact failure this component exists to prevent.
    migrationJdbc.update("UPDATE core.learning_domain SET domain_type = 'ACADEMIC' WHERE code = ?",
        "KAFKA");
    try {
      assertThat(assembler.forSkill("KAFKA_TOPIC"))
          .get()
          .extracting(DomainContext::domainType)
          .isEqualTo(DomainType.ACADEMIC);
    } finally {
      migrationJdbc.update(
          "UPDATE core.learning_domain SET domain_type = 'TECHNOLOGY' WHERE code = ?", "KAFKA");
    }
  }

  @Test
  @DisplayName("an unknown skill yields no context rather than a fabricated one")
  void unknownSkillYieldsNothing() {
    assertThat(assembler.forSkill("NO_SUCH_SKILL_ANYWHERE")).isEmpty();
    assertThat(assembler.forSkill(null)).isEmpty();
    assertThat(assembler.forSkill("  ")).isEmpty();
  }

  @Test
  @DisplayName("the curriculum version is the published one")
  void curriculumVersionIsThePublishedOne() {
    // A draft version describes a curriculum the platform has not adopted, and a retired one
    // describes a syllabus it has withdrawn. Grounding an agent in either would be reasoning from a
    // curriculum no learner is being taught.
    Optional<DomainContext> context = assembler.forSkill("KAFKA_TOPIC");

    assertThat(context).isPresent();
    String published = migrationJdbc.queryForObject("""
        SELECT cv.version_code FROM core.curriculum_version cv
          JOIN core.learning_domain d ON d.id = cv.domain_id
         WHERE d.code = 'KAFKA' AND cv.status = 'PUBLISHED'
         ORDER BY cv.published_at DESC LIMIT 1
        """, String.class);
    assertThat(context.get().curriculumVersion()).isEqualTo(published);
  }

  @Test
  @DisplayName("a learner goal becomes a typed goal context")
  void learnerGoalBecomesTypedGoalContext() {
    LearnerGoal goal = new LearnerGoal(
        UUID.randomUUID(), "KAFKA", new BigDecimal("0.8000"),
        LocalDate.of(2027, 3, 31), Instant.now(), Instant.now());

    Optional<LearningGoalContext> context = assembler.forLearnerGoal(Optional.of(goal));

    assertThat(context).isPresent();
    // Stated explicitly rather than left implicit, so agents branch on a goal type from the start
    // instead of assuming the code names a domain.
    assertThat(context.get().goalType()).isEqualTo(GoalType.LEARNING_DOMAIN);
    assertThat(context.get().goalCode()).isEqualTo("KAFKA");
    assertThat(context.get().targetDate()).isEqualTo(LocalDate.of(2027, 3, 31));
  }

  @Test
  @DisplayName("a learner with no goal produces no goal context")
  void noGoalProducesNoContext() {
    assertThat(assembler.forLearnerGoal(Optional.empty())).isEmpty();
  }

  @Test
  @DisplayName("the AI runtime role still cannot read any of this")
  void theAiRuntimeRoleRemainsLockedOut() {
    // The assembler runs as the core runtime role inside Spring. M1-T03's boundary must be
    // unaffected by adding a column and a read path.
    Boolean provisioned = migrationJdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime')",
        Boolean.class);
    if (!Boolean.TRUE.equals(provisioned)) {
      // The role is created by infrastructure/docker/postgres-init, not by a migration, so it is
      // legitimately absent here. AiRuntimeBoundaryIntegrationTests covers the provisioned case.
      return;
    }
    assertThat(migrationJdbc.queryForObject("""
        SELECT has_table_privilege('ramals_ai_runtime', 'core.learning_domain', 'SELECT')
        """, Boolean.class))
        .as("ramals_ai_runtime must not gain read access through this change")
        .isFalse();
  }
}
