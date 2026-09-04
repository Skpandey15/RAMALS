package io.ramals.learningplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The upgrade a real deployment performs: V045 through V049 applied to a database that is already
 * at V044 and already holds data.
 *
 * <p>An empty-database install proves the migrations parse. It cannot prove the thing that actually
 * breaks upgrades -- a new constraint that existing rows violate, or a new column that existing
 * rows cannot satisfy. Both of those only appear when there are rows there first, so this fixture
 * stops at V044, writes the state a running system would have, and then migrates forward.
 *
 * <p>The rows are written with the V044 column set, which is what makes them a genuine test: an
 * attempt with no {@code selection_policy}, evidence with no coverage arrays, and a snapshot with
 * no status policy -- because at V044 none of those columns existed.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class CoverageUpgradePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  /** The released schema this upgrade starts from. */
  private static final String PREVIOUS_RELEASED_VERSION = "44";

  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID ASSESSMENT_VERSION =
      UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ITEM_BROKER = UUID.fromString("01900000-0000-7000-8000-000000000411");

  private static final UUID LEARNER = UUID.fromString("01900000-0000-7000-8000-0000000000b1");
  private static final UUID ATTEMPT = UUID.fromString("01900000-0000-7000-8000-0000000000b2");
  private static final UUID EVIDENCE = UUID.fromString("01900000-0000-7000-8000-0000000000b3");
  private static final UUID SNAPSHOT = UUID.fromString("01900000-0000-7000-8000-0000000000b4");
  private static final UUID RESPONSE = UUID.fromString("01900000-0000-7000-8000-0000000000b5");

  private static String databaseUrl;
  private static int migrationsApplied;
  private static JdbcTemplate runtimeJdbc;

  @BeforeAll
  static void upgradeFromThePreviousRelease() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    resetSchemas();

    // 1. The previous release's schema, and nothing newer.
    Flyway atPreviousRelease = flyway().target(MigrationVersion.fromVersion(
        PREVIOUS_RELEASED_VERSION)).load();
    atPreviousRelease.migrate();

    // 2. The state a running system would be holding when the upgrade lands.
    seedPreUpgradeState();

    // 3. The upgrade itself.
    migrationsApplied = flyway().load().migrate().migrationsExecuted;

    runtimeJdbc = new JdbcTemplate(
        new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD));
  }

  @Test
  void onlyTheNineNewMigrationsAreAppliedAndTheSchemaValidates() {
    // Exactly V045 through V053. A different number means a migration was renumbered, skipped, or
    // re-applied, all of which are upgrade hazards a "did it succeed" assertion would miss.
    assertThat(migrationsApplied).isEqualTo(9);
    assertThat(flyway().load().validateWithResult().validationSuccessful).isTrue();
    // Read as the migration role: the runtime role is denied this table on purpose, so that the
    // application can never rewrite its own migration history.
    assertThat(appliedVersionsAfterTheUpgrade())
        .containsExactly("045", "046", "047", "048", "049", "050", "051", "052", "053");
  }

  @Test
  void rowsWrittenBeforeTheUpgradeSurviveItUnchanged() {
    // The upgrade adds columns and rows; it must not have touched anything already there.
    assertThat(runtimeJdbc.queryForObject(
        "SELECT status FROM core.assessment_attempt WHERE id = ?", String.class, ATTEMPT))
        .isEqualTo("COMPLETED");
    assertThat(runtimeJdbc.queryForObject(
        "SELECT normalized_score FROM ledger.evidence WHERE id = ?", java.math.BigDecimal.class,
        EVIDENCE)).isEqualByComparingTo("1.0000");
    assertThat(runtimeJdbc.queryForObject(
        "SELECT mastery_status FROM ledger.mastery_snapshot WHERE id = ?", String.class, SNAPSHOT))
        .isEqualTo("DEVELOPING");
    assertThat(runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_response WHERE id = ?", Integer.class, RESPONSE))
        .isEqualTo(1);
  }

  @Test
  void preUpgradeRowsCarryNoFabricatedCoverage() {
    // The honest value for a row written before anything recorded coverage is NULL, not an empty
    // array and not a guess. Backfilling would invent a claim about what a learner was measured on.
    assertThat(runtimeJdbc.queryForObject(
        "SELECT covered_objective_ids IS NULL FROM ledger.evidence WHERE id = ?", Boolean.class,
        EVIDENCE)).isTrue();
    assertThat(runtimeJdbc.queryForObject(
        "SELECT covered_difficulty_bands IS NULL FROM ledger.evidence WHERE id = ?", Boolean.class,
        EVIDENCE)).isTrue();
    assertThat(runtimeJdbc.queryForObject(
        "SELECT selection_policy IS NULL FROM core.assessment_attempt WHERE id = ?", Boolean.class,
        ATTEMPT)).isTrue();
    assertThat(runtimeJdbc.queryForObject(
        "SELECT status_policy_version IS NULL FROM ledger.mastery_snapshot WHERE id = ?",
        Boolean.class, SNAPSHOT)).isTrue();
  }

  @Test
  void anAttemptThatPredatesSelectionKeepsItsResponses() {
    // The response guard only binds attempts that have a recorded selection. The pre-upgrade
    // attempt has none -- it was legitimately served the whole pool -- so its existing response
    // must survive, and the guard must not retroactively invalidate it.
    assertThat(runtimeJdbc.queryForObject("""
        SELECT count(*) FROM core.assessment_attempt_item WHERE attempt_id = ?
        """, Integer.class, ATTEMPT)).isZero();
    assertThat(runtimeJdbc.queryForObject("""
        SELECT item_version_id FROM core.assessment_response WHERE attempt_id = ?
        """, UUID.class, ATTEMPT)).isEqualTo(ITEM_BROKER);
  }

  @Test
  void theUpgradeInstallsTheNewObjectsTheV2PolicyDependsOn() {
    assertThat(tableExists("core", "assessment_attempt_item")).isTrue();
    assertThat(tableExists("core", "assessment_item_objective")).isTrue();
    assertThat(tableExists("core", "assessment_item_lineage")).isTrue();
    // The seeded tagging arrives with the upgrade, on a database that already held the v1 items:
    // 5 for v1 (V046) plus 35 for the v2 bank this same upgrade authors (V049).
    assertThat(runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_item_objective", Integer.class)).isEqualTo(40);
    // Every item in the database -- the five pre-existing v1 items this upgrade backfills, plus
    // the 35 v2 items it authors -- has a logical identity by the time the upgrade finishes.
    assertThat(runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_item_lineage", Integer.class)).isEqualTo(40);
    assertThat(triggerNames()).contains(
        "trg_assessment_attempt_item_guard",
        "trg_assessment_item_objective_skill_match",
        "trg_assessment_response_guard");
    // V050: the Kafka v2 version declares the adaptive selector; v1 still declares none (NULL,
    // meaning V1's legacy selector, unchanged) even after the upgrade.
    assertThat(runtimeJdbc.queryForObject(
        "SELECT selection_policy_version FROM core.assessment_version WHERE id = ?", String.class,
        UUID.fromString("01900000-0000-7000-8000-000000000403")))
        .isEqualTo("DIAGNOSTIC_SELECTION_V2");
    assertThat(runtimeJdbc.queryForObject(
        "SELECT selection_policy_version FROM core.assessment_version WHERE id = ?", String.class,
        UUID.fromString("01900000-0000-7000-8000-000000000402")))
        .isNull();
  }

  /** The migrations this upgrade applied, in order, read with the role that owns the history. */
  private static List<String> appliedVersionsAfterTheUpgrade() {
    JdbcTemplate migrationJdbc = new JdbcTemplate(
        new DriverManagerDataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD));
    return migrationJdbc.queryForList("""
        SELECT version FROM core.flyway_schema_history
        WHERE success AND version::numeric > ?::numeric
        ORDER BY version::numeric
        """, String.class, PREVIOUS_RELEASED_VERSION);
  }

  private static boolean tableExists(String schema, String table) {
    return Boolean.TRUE.equals(runtimeJdbc.queryForObject("""
        SELECT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = ? AND table_name = ?)
        """, Boolean.class, schema, table));
  }

  private static List<String> triggerNames() {
    return runtimeJdbc.queryForList(
        "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal", String.class);
  }

  /**
   * Writes the pre-upgrade state using only columns that exist at V044.
   *
   * <p>Through the migration role, because the point is to represent rows a previous release left
   * behind rather than to exercise the runtime's privileges.
   */
  private static void seedPreUpgradeState() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO core.learner (id, subject, status) VALUES ('" + LEARNER
              + "', 'pre-upgrade-learner', 'ACTIVE')");
      statement.executeUpdate("""
          INSERT INTO core.assessment_attempt
            (id, learner_id, assessment_version_id, status, idempotency_key)
          VALUES ('%s', '%s', '%s', 'IN_PROGRESS', 'pre-upgrade-key')
          """.formatted(ATTEMPT, LEARNER, ASSESSMENT_VERSION));
      statement.executeUpdate("""
          INSERT INTO core.assessment_response
            (id, attempt_id, item_version_id, response_jsonb, is_correct)
          VALUES ('%s', '%s', '%s', '{"selectedOptions":["B"]}'::jsonb, true)
          """.formatted(RESPONSE, ATTEMPT, ITEM_BROKER));
      statement.executeUpdate(
          "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = '" + ATTEMPT + "'");
      statement.executeUpdate("""
          INSERT INTO ledger.evidence
            (id, learner_id, skill_id, evidence_type, source_type, source_attempt_id,
             source_assessment_version_id, scoring_version, lineage_key, observed_score,
             normalized_score, items_answered, items_correct, interaction_id)
          VALUES ('%s', '%s', '%s', 'DIAGNOSTIC', 'ASSESSMENT_ATTEMPT', '%s', '%s',
                  'DIAGNOSTIC_SCORING_V1', 'pre-upgrade-lineage', 1.0000, 1.0000, 1, 1,
                  'pre-upgrade-interaction')
          """.formatted(EVIDENCE, LEARNER, BROKER_SKILL, ATTEMPT, ASSESSMENT_VERSION));
      statement.executeUpdate("""
          INSERT INTO core.learner_skill_aggregate (learner_id, skill_id, curriculum_version_id)
          VALUES ('%s', '%s', '%s')
          """.formatted(LEARNER, BROKER_SKILL, CURRICULUM));
      // Stamped with the V1 identifiers, because that is what computed it.
      statement.executeUpdate("""
          INSERT INTO ledger.mastery_snapshot
            (id, learner_id, skill_id, curriculum_version_id, aggregate_version, mastery_score,
             mastery_status, threshold, evidence_confidence, confidence_threshold, evidence_count,
             items_considered, algorithm_version, confidence_algorithm_version, interaction_id)
          VALUES ('%s', '%s', '%s', '%s', 1, 1.0000, 'DEVELOPING', 0.8000, 0.3300, 0.7500, 1, 1,
                  'WEIGHTED_MASTERY_V1', 'EVIDENCE_CONFIDENCE_V1', 'pre-upgrade-interaction')
          """.formatted(SNAPSHOT, LEARNER, BROKER_SKILL, CURRICULUM));
    }
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
    return Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true);
  }

  private static void resetSchemas() throws SQLException {
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
      throw new IllegalStateException("Missing required environment variable: " + name);
    }
    return value;
  }
}
