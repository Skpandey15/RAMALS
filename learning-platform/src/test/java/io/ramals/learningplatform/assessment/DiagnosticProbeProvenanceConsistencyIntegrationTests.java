package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V055's hardening pass: {@code core.diagnostic_probe_provenance} is authoritative audit
 * provenance, not a log line, so its internal consistency is enforced at the database boundary
 * rather than trusted from the application writer alone -- the same discipline
 * {@code core.protect_assessment_response} already holds itself to. Every case here inserts a row
 * directly (bypassing {@code ProbeProvenanceRepository}, which never produces an inconsistent row
 * in the first place) specifically to prove the database itself refuses one.
 *
 * <p>Real KAFKA v2 content throughout: {@code ACKS_MCQ_A1} (d11, ACKS_DURABILITY_TRADEOFFS) and
 * {@code ACKS_MCQ_A2} (d12, PRODUCER_IDEMPOTENCE) are the real trigger/target pair V054's real
 * {@code e01} relationship (d11 -&gt; d12, ROOT_CAUSE_PROBE, PUBLISHED) already authorizes -- the
 * same fixture {@code HypothesisDrivenProbeSelectionPersistenceIntegrationTests} uses for its own
 * flagship end-to-end case.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticProbeProvenanceConsistencyIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");

  // Real objective ids, v2 curriculum -- verified against a real migrated database.
  private static final UUID ACKS_DURABILITY_TRADEOFFS =
      UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID PRODUCER_IDEMPOTENCE =
      UUID.fromString("01900000-0000-7000-8000-000000000d12");
  private static final UUID ACKS_SEMANTICS = UUID.fromString("01900000-0000-7000-8000-000000000d10");

  // Real item ids, all tagged exactly as the constant name says.
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625"); // d11
  private static final UUID ACKS_MCQ_A2 = UUID.fromString("01900000-0000-7000-8000-000000000626"); // d12
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624"); // d11, NOT presented anywhere below
  private static final UUID ACKS_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000622"); // d10

  // e01: d11 -> d12, ROOT_CAUSE_PROBE, PUBLISHED (V054's real seed).
  private static final UUID E01_ROOT_CAUSE_PROBE =
      UUID.fromString("01900000-0000-7000-8000-000000000e01");
  // e03: d11 -> d10, CONTRADICTION_CHECK, PUBLISHED (V054's real seed) -- a real, PUBLISHED, but
  // mismatched relationship, for the "fields don't match" case.
  private static final UUID E03_CONTRADICTION_CHECK =
      UUID.fromString("01900000-0000-7000-8000-000000000e03");

  private static String databaseUrl;
  private LearnerRepository learners;
  private JdbcTemplate runtimeJdbc;

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

  // -------------------------------------------------------------------------------------------
  // Positive control: a fully consistent row is accepted -- proving the new constraints reject
  // only genuinely inconsistent rows, not every row.
  // -------------------------------------------------------------------------------------------

  @Test
  void aFullyConsistentRowIsAccepted() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-consistent");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);

    insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_A1,
        ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE);

    Integer count = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.diagnostic_probe_provenance WHERE attempt_id = ?",
        Integer.class, newAttemptId);
    assertThat(count).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Fact 1: source_item_version_id must have actually been presented in source_attempt_id.
  // -------------------------------------------------------------------------------------------

  @Test
  void aSourceItemNeverPresentedInTheSourceAttemptIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-source-item-not-presented");
    // Presents ACKS_MCQ_A1, not ACKS_MCQ_I2 -- claiming ACKS_MCQ_I2 as the source item is false.
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);

    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_I2,
        ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Fact 2: source_objective_id must actually tag source_item_version_id.
  // -------------------------------------------------------------------------------------------

  @Test
  void aSourceObjectiveThatDoesNotTagTheSourceItemIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-source-objective-mismatch");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);

    // ACKS_MCQ_A1 is tagged to d11, never to d12 -- claiming d12 as its objective is false.
    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_A1,
        PRODUCER_IDEMPOTENCE, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Fact 3: target_objective_id must actually tag the selected item_version_id.
  // -------------------------------------------------------------------------------------------

  @Test
  void aTargetObjectiveThatDoesNotTagTheSelectedItemIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-target-objective-mismatch");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);

    // ACKS_MCQ_A2 is tagged to d12, never to d11 -- claiming d11 as its objective is false.
    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_A1,
        ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", ACKS_DURABILITY_TRADEOFFS, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Facts 4/5: authorizing_relationship_id required for the two hand-authored types, forbidden
  // for the two graph-derived ones.
  // -------------------------------------------------------------------------------------------

  @Test
  void aRootCauseProbeRowWithNoAuthorizingRelationshipIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-missing-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);

    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_A1,
        ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aSameObjectiveConfirmationRowWithAnAuthorizingRelationshipIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-forbidden-authorization");
    // ACKS_MCQ_F (d10) is a real, valid SAME_OBJECTIVE_CONFIRMATION source/target pair on itself.
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_F);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_I2);

    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_I2, sourceAttemptId, ACKS_MCQ_F,
        ACKS_SEMANTICS, "SAME_OBJECTIVE_CONFIRMATION", ACKS_SEMANTICS, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------------------------
  // A present authorizing relationship must exist, be PUBLISHED, and exactly match.
  // -------------------------------------------------------------------------------------------

  @Test
  void anAuthorizingRelationshipIdThatDoesNotExistIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-nonexistent-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);

    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_A1,
        ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anAuthorizingRelationshipThatIsOnlyDraftIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-draft-authorization");
    // A fresh (source, target, type) triple -- ACKS_SEMANTICS (d10) -> PRODUCER_IDEMPOTENCE (d12) --
    // that collides with none of V054's real e01/e02/e03 rows, so this draft can be inserted at all
    // (the table's own UNIQUE (source, target, type) constraint would otherwise reject a duplicate
    // of e01 outright, for the right reason but not the one this test is about).
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_F);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);
    UUID draftRelationshipId = insertDraftRelationship(
        ACKS_SEMANTICS, PRODUCER_IDEMPOTENCE, "ROOT_CAUSE_PROBE");

    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_F,
        ACKS_SEMANTICS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, draftRelationshipId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anAuthorizingRelationshipWhoseFieldsDoNotMatchTheProvenanceRowIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("provenance-mismatched-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID newAttemptId = inProgressAttemptSelecting(learner.id(), ACKS_MCQ_A2);

    // E03 is real and PUBLISHED, but authorizes d11 -> d10 / CONTRADICTION_CHECK -- citing it for
    // a d11 -> d12 / ROOT_CAUSE_PROBE row is a real relationship pointed at the wrong claim.
    assertThatThrownBy(() -> insertProvenance(newAttemptId, ACKS_MCQ_A2, sourceAttemptId, ACKS_MCQ_A1,
        ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E03_CONTRADICTION_CHECK))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID completedAttemptPresenting(UUID learnerId, UUID itemVersionId) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "consistency-source-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
    return attemptId;
  }

  private UUID inProgressAttemptSelecting(UUID learnerId, UUID itemVersionId) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "consistency-target-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'HYPOTHESIS_DRIVEN_PROBE')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    return attemptId;
  }

  private void insertProvenance(
      UUID attemptId, UUID itemVersionId, UUID sourceAttemptId, UUID sourceItemVersionId,
      UUID sourceObjectiveId, String relationshipType, UUID targetObjectiveId,
      UUID authorizingRelationshipId) {
    runtimeJdbc.update("""
        INSERT INTO core.diagnostic_probe_provenance
          (id, attempt_id, item_version_id, source_attempt_id, source_item_version_id,
           source_objective_id, relationship_type, target_objective_id, authorizing_relationship_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), attemptId, itemVersionId, sourceAttemptId, sourceItemVersionId,
        sourceObjectiveId, relationshipType, targetObjectiveId, authorizingRelationshipId);
  }

  private UUID insertDraftRelationship(
      UUID sourceObjectiveId, UUID targetObjectiveId, String relationshipType) {
    UUID id = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.diagnostic_probe_relationship
          (id, source_objective_id, target_objective_id, relationship_type, status, rationale)
        VALUES (?, ?, ?, ?, 'DRAFT', 'Test-only draft relationship for the not-yet-published case.')
        """, id, sourceObjectiveId, targetObjectiveId, relationshipType);
    return id;
  }

  private void wire() {
    if (runtimeJdbc == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      learners = new LearnerRepository(runtimeJdbc);
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
