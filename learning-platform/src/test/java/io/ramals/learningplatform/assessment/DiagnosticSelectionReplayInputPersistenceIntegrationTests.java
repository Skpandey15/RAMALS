package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateProbe;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M2-ADR-034 Amendment 4 against real PostgreSQL: {@code core.diagnostic_selection_replay_input} /
 * {@code core.diagnostic_selection_replay_candidate_probe} are authoritative historical provenance,
 * append-only, and internally consistent at the database boundary -- the same discipline V055's
 * {@code core.diagnostic_probe_provenance} already holds itself to (mirrored here via {@link
 * DiagnosticProbeProvenanceConsistencyIntegrationTests}'s own fixture: real KAFKA v2 content, {@code
 * ACKS_MCQ_A1}/{@code ACKS_MCQ_A2} (d11/d12) and the real, PUBLISHED {@code e01} relationship).
 *
 * <p>Every negative case here inserts a row directly, bypassing {@link
 * DiagnosticSelectionReplayInputRepository} (which never produces an inconsistent row in the first
 * place), specifically to prove the database itself refuses one.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticSelectionReplayInputPersistenceIntegrationTests {

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
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624"); // d11, unused elsewhere
  private static final UUID ACKS_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000622"); // d10

  // e01: d11 -> d12, ROOT_CAUSE_PROBE, PUBLISHED (V054's real seed).
  private static final UUID E01_ROOT_CAUSE_PROBE =
      UUID.fromString("01900000-0000-7000-8000-000000000e01");
  // e03: d11 -> d10, CONTRADICTION_CHECK, PUBLISHED (V054's real seed) -- real, PUBLISHED, mismatched.
  private static final UUID E03_CONTRADICTION_CHECK =
      UUID.fromString("01900000-0000-7000-8000-000000000e03");

  private static String databaseUrl;
  private LearnerRepository learners;
  private JdbcTemplate runtimeJdbc;
  private DiagnosticSelectionReplayInputRepository replayInputs;

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
  // Positive round trips.
  // -------------------------------------------------------------------------------------------

  @Test
  void aFullyConsistentActivatedSnapshotIsPersistedAndReadBackExactly() {
    wire();
    Learner learner = learners.provisionForSubject("replay-consistent");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID secondProbeItem = insertItemTaggedTo(PRODUCER_IDEMPOTENCE, "REPLAY_EXTRA_D12");

    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE);
    // Two surviving candidate probes for the SAME hypothesis -- Amendment 4 §H/§20's own
    // "multiple candidates per hypothesis" requirement.
    List<CandidateProbe> candidates = List.of(
        new CandidateProbe(ACKS_MCQ_A2, hypothesis, true),
        new CandidateProbe(secondProbeItem, hypothesis, true));
    HypothesisDiscriminationDiagnosticSelector.Decision decision = new HypothesisDiscriminationDiagnosticSelector.Decision(
        Optional.empty(), sourceAttemptId, 1, 1, 2, 1, "APPLICABLE", "SCORABLE",
        java.math.BigDecimal.ZERO, false, V6FallbackReason.ALL_SCORES_ZERO, List.of(hypothesis), candidates);

    replayInputs.insert(destinationAttemptId, decision);

    DiagnosticSelectionReplayInput header =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();
    List<CandidateProbe> readBack = replayInputs.findCandidateProbes(header.id());
    List<DiagnosticHypothesis> actionable = replayInputs.findActionableHypotheses(header.id());

    assertThat(header.sourceAttemptId()).isEqualTo(sourceAttemptId);
    assertThat(header.snapshotContractVersion())
        .isEqualTo(DiagnosticSelectionReplayInputRepository.SNAPSHOT_CONTRACT_VERSION);
    assertThat(header.candidateProbeCount())
        .as("header's own count must match the actual persisted child-row count")
        .isEqualTo(readBack.size())
        .isEqualTo(2);
    assertThat(readBack).containsExactlyInAnyOrder(
        new CandidateProbe(ACKS_MCQ_A2, hypothesis, true),
        new CandidateProbe(secondProbeItem, hypothesis, true));
    // Full five-field DiagnosticHypothesis identity round-trips verbatim, never a partial key.
    assertThat(actionable).containsExactly(hypothesis);
  }

  @Test
  void noSourceAttemptSnapshotPersistsNullSourceAndNoCandidateRows() {
    wire();
    Learner learner = learners.provisionForSubject("replay-no-source");
    UUID destinationAttemptId = inProgressAttempt(learner.id());

    replayInputs.insert(destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.noSourceAttemptDecision());

    DiagnosticSelectionReplayInput header =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();
    assertThat(header.sourceAttemptId()).isNull();
    assertThat(header.activated()).isFalse();
    assertThat(header.fallbackReason()).isEqualTo(V6FallbackReason.NO_SOURCE_ATTEMPT);
    assertThat(header.candidateProbeCount()).isZero();
    assertThat(replayInputs.findCandidateProbes(header.id())).isEmpty();
    assertThat(replayInputs.findActionableHypotheses(header.id())).isEmpty();
  }

  @Test
  void nullableAuthorizingRelationshipRoundTripsAsNullForSameObjectiveConfirmation() {
    wire();
    Learner learner = learners.provisionForSubject("replay-nullable-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_F);
    UUID destinationAttemptId = inProgressAttempt(learner.id());

    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        ACKS_MCQ_F, ACKS_SEMANTICS, ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION, ACKS_SEMANTICS, null);
    List<CandidateProbe> candidates = List.of(new CandidateProbe(ACKS_MCQ_F, hypothesis, true));
    HypothesisDiscriminationDiagnosticSelector.Decision decision = new HypothesisDiscriminationDiagnosticSelector.Decision(
        Optional.empty(), sourceAttemptId, 1, 1, 1, 0, null, null, null, false,
        V6FallbackReason.SINGLE_CANDIDATE_TOTAL, List.of(hypothesis), candidates);

    replayInputs.insert(destinationAttemptId, decision);

    DiagnosticSelectionReplayInput header =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();
    DiagnosticHypothesis readBack = replayInputs.findActionableHypotheses(header.id()).get(0);
    assertThat(readBack.authorizingRelationshipId()).isNull();
  }

  // -------------------------------------------------------------------------------------------
  // Amendment 4 governing principle: a persisted snapshot is immune to the MVCC race that
  // falsified Amendment 3 §V's original created_at-based replay claim (see the now-removed
  // AssessmentRepository#findLearnerExposedLogicalItemIdsBefore and its own superseded tests).
  // -------------------------------------------------------------------------------------------

  @Test
  void persistedSnapshotIsImmuneToTheConcurrentUncommittedAttemptRace() throws SQLException {
    wire();
    Learner learner = learners.provisionForSubject("replay-concurrency-immune");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());

    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE);
    List<CandidateProbe> candidates = List.of(new CandidateProbe(ACKS_MCQ_A2, hypothesis, true));
    HypothesisDiscriminationDiagnosticSelector.Decision decision = new HypothesisDiscriminationDiagnosticSelector.Decision(
        Optional.empty(), sourceAttemptId, 1, 1, 1, 0, null, null, null, false,
        V6FallbackReason.SINGLE_CANDIDATE_TOTAL, List.of(hypothesis), candidates);

    // The snapshot is written -- synchronously, at decision time -- before any concurrent activity.
    replayInputs.insert(destinationAttemptId, decision);
    DiagnosticSelectionReplayInput headerBefore =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();

    // A concurrent, later-completing attempt for the SAME learner -- exactly the interleaving that
    // falsified created_at-bounded replay: it starts, and does not commit, until after the snapshot
    // above was already durably written. Under the OLD (superseded) design this would have changed
    // what a live "most recent completed attempt" or exposure-cutoff query returned; the persisted
    // snapshot must be completely unaffected by it, because replay reads only the fixed snapshot
    // rows below, never live/current state.
    // uq_assessment_attempt_one_active is scoped per assessment_version_id, not per learner -- the
    // exact gap the original MVCC race exploited -- so the concurrent attempt targets a SECOND
    // version rather than colliding with the destination attempt's own IN_PROGRESS row.
    UUID concurrentVersionId = freshDraftVersionSharingAssessment();
    UUID concurrentAttempt = UUID.randomUUID();
    try (Connection uncommitted = DriverManager.getConnection(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD)) {
      uncommitted.setAutoCommit(false);
      try (var statement = uncommitted.prepareStatement("""
          INSERT INTO core.assessment_attempt (id, learner_id, assessment_version_id, status, idempotency_key)
          VALUES (?, ?, ?, 'IN_PROGRESS', ?)
          """)) {
        statement.setObject(1, concurrentAttempt);
        statement.setObject(2, learner.id());
        statement.setObject(3, concurrentVersionId);
        statement.setString(4, "replay-concurrency-fixture-" + concurrentAttempt);
        statement.executeUpdate();
      }
      uncommitted.commit();
    }
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", concurrentAttempt);

    DiagnosticSelectionReplayInput headerAfter =
        replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow();
    List<CandidateProbe> candidatesAfter = replayInputs.findCandidateProbes(headerAfter.id());

    assertThat(headerAfter).isEqualTo(headerBefore);
    assertThat(headerAfter.sourceAttemptId())
        .as("the persisted source attempt never changes because a newer attempt now exists")
        .isEqualTo(sourceAttemptId);
    assertThat(candidatesAfter).containsExactly(new CandidateProbe(ACKS_MCQ_A2, hypothesis, true));
  }

  // -------------------------------------------------------------------------------------------
  // Append-only protection and IN_PROGRESS-only insertion (header table).
  // -------------------------------------------------------------------------------------------

  @Test
  void headerRowIsRejectedWhenDestinationAttemptIsNotInProgress() {
    wire();
    Learner learner = learners.provisionForSubject("replay-header-not-in-progress");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A2);

    // ERRCODE 55000 (object not in prerequisite state) -- Spring's default translator does not map
    // SQLSTATE class 55 to DataIntegrityViolationException, only to the broader DataAccessException,
    // the same as V055's own protect_probe_provenance immutability/in-progress guards would.
    assertThatThrownBy(() -> insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void headerRowUpdateIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("replay-header-update-rejected");
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    replayInputs.insert(destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.noSourceAttemptDecision());
    UUID replayInputId = replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow().id();

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.diagnostic_selection_replay_input SET activated = TRUE WHERE id = ?", replayInputId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void headerRowDeleteIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("replay-header-delete-rejected");
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    replayInputs.insert(destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.noSourceAttemptDecision());
    UUID replayInputId = replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow().id();

    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.diagnostic_selection_replay_input WHERE id = ?", replayInputId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void destinationAttemptIdUniquenessIsEnforced() {
    wire();
    Learner learner = learners.provisionForSubject("replay-header-unique-destination");
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    replayInputs.insert(destinationAttemptId, HypothesisDiscriminationDiagnosticSelector.noSourceAttemptDecision());

    assertThatThrownBy(() -> insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, null, false,
        V6FallbackReason.NO_SOURCE_ATTEMPT))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Append-only protection, IN_PROGRESS gating, Fact-1 check, and composite-consistency discipline
  // (candidate-probe table).
  // -------------------------------------------------------------------------------------------

  @Test
  void candidateRowUpdateIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-update-rejected");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertConsistentSnapshot(destinationAttemptId, sourceAttemptId);

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.diagnostic_selection_replay_candidate_probe SET admission_ordinal = 99 "
            + "WHERE replay_input_id = ?", replayInputId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void candidateRowDeleteIsRejected() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-delete-rejected");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertConsistentSnapshot(destinationAttemptId, sourceAttemptId);

    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.diagnostic_selection_replay_candidate_probe WHERE replay_input_id = ?", replayInputId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void candidateRowIsRejectedWhenReplayInputIdDoesNotExist() {
    wire();
    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), UUID.randomUUID(), 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowIsRejectedWhenTriggerItemWasNotPresentedInTheSourceAttempt() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-trigger-not-presented");
    // Presents ACKS_MCQ_A1, not ACKS_MCQ_I2 -- claiming ACKS_MCQ_I2 as the trigger item is false.
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);

    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_I2, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowCompositeFkRejectsTriggerObjectiveMismatch() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-trigger-objective-mismatch");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);

    // ACKS_MCQ_A1 is tagged to d11, never to d12 -- claiming d12 as its trigger objective is false.
    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, PRODUCER_IDEMPOTENCE, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowCompositeFkRejectsProbeTargetObjectiveMismatch() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-target-objective-mismatch");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);

    // ACKS_MCQ_A2 is tagged to d12, never to d11 -- claiming d11 as its target objective is false.
    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", ACKS_DURABILITY_TRADEOFFS, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowRequiresAuthorizingRelationshipForRootCauseProbe() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-missing-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);

    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowForbidsAuthorizingRelationshipForSameObjectiveConfirmation() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-forbidden-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_F);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);

    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_F,
        ACKS_MCQ_F, ACKS_SEMANTICS, "SAME_OBJECTIVE_CONFIRMATION", ACKS_SEMANTICS, E01_ROOT_CAUSE_PROBE))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowIsRejectedWhenAuthorizingRelationshipDoesNotExist() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-nonexistent-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);

    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowIsRejectedWhenAuthorizingRelationshipIsOnlyDraft() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-draft-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);
    // A fresh (source, target, type) triple colliding with none of V054's real e01/e02/e03 rows.
    UUID draftRelationshipId = insertDraftRelationship(ACKS_SEMANTICS, PRODUCER_IDEMPOTENCE, "ROOT_CAUSE_PROBE");

    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_SEMANTICS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, draftRelationshipId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowIsRejectedWhenAuthorizingRelationshipFieldsDoNotMatch() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-mismatched-authorization");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);

    // E03 is real and PUBLISHED, but authorizes d11 -> d10 / CONTRADICTION_CHECK -- citing it for a
    // d11 -> d12 / ROOT_CAUSE_PROBE row is a real relationship pointed at the wrong claim.
    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E03_CONTRADICTION_CHECK))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void candidateRowDuplicateAdmissionIsRejectedByUniqueConstraint() {
    wire();
    Learner learner = learners.provisionForSubject("replay-candidate-duplicate-admission");
    UUID sourceAttemptId = completedAttemptPresenting(learner.id(), ACKS_MCQ_A1);
    UUID destinationAttemptId = inProgressAttempt(learner.id());
    UUID replayInputId = insertHeaderDirect(UUID.randomUUID(), destinationAttemptId, sourceAttemptId, false,
        V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);
    insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE);

    assertThatThrownBy(() -> insertCandidateDirect(UUID.randomUUID(), replayInputId, 1, ACKS_MCQ_A2,
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, "ROOT_CAUSE_PROBE", PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE))
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
        """, attemptId, learnerId, ASSESSMENT_V2, "replay-source-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
    return attemptId;
  }

  private UUID inProgressAttempt(UUID learnerId) {
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "replay-destination-fixture-" + attemptId);
    return attemptId;
  }

  /** A second, unrelated DRAFT version of the same assessment, for a concurrent attempt that must
   * NOT collide with {@code uq_assessment_attempt_one_active} (scoped per version) against the
   * destination attempt's own IN_PROGRESS row. */
  private UUID freshDraftVersionSharingAssessment() {
    UUID assessmentId = runtimeJdbc.queryForObject(
        "SELECT assessment_id FROM core.assessment_version WHERE id = ?", UUID.class, ASSESSMENT_V2);
    UUID curriculumVersionId = runtimeJdbc.queryForObject(
        "SELECT curriculum_version_id FROM core.assessment_version WHERE id = ?", UUID.class, ASSESSMENT_V2);
    UUID versionId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_version (id, assessment_id, curriculum_version_id, version_code, status)
        VALUES (?, ?, ?, ?, 'DRAFT')
        """, versionId, assessmentId, curriculumVersionId, "replay-concurrency-" + versionId);
    return versionId;
  }

  /** Inserts a new item, tagged only to {@code objectiveId}, reusing {@code ACKS_MCQ_A1}'s own real
   * {@code skill_id} so no new curriculum fixture is needed. */
  private UUID insertItemTaggedTo(UUID objectiveId, String itemCode) {
    UUID skillId = runtimeJdbc.queryForObject(
        "SELECT skill_id FROM core.assessment_item_version WHERE id = ?", UUID.class, ACKS_MCQ_A1);
    UUID itemVersionId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_item_version
          (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
           answer_key_jsonb, difficulty, display_order, trust_state, verified_by, verified_at)
        VALUES (?, ?, ?, ?, 'SINGLE_CHOICE', 'Replay fixture stem.',
                '[{"id":"A","text":"a"},{"id":"B","text":"b"}]'::jsonb, '{"correct":["A"]}'::jsonb,
                'FOUNDATIONAL', 900, 'VERIFIED_CONTENT', 'replay-fixture', CURRENT_TIMESTAMP)
        """, itemVersionId, ASSESSMENT_V2, skillId, itemCode);
    runtimeJdbc.update(
        "INSERT INTO core.assessment_item_objective (item_version_id, objective_id) VALUES (?, ?)",
        itemVersionId, objectiveId);
    return itemVersionId;
  }

  /** A consistent header + single candidate row, inserted via the real repository. */
  private UUID insertConsistentSnapshot(UUID destinationAttemptId, UUID sourceAttemptId) {
    DiagnosticHypothesis hypothesis = new DiagnosticHypothesis(
        ACKS_MCQ_A1, ACKS_DURABILITY_TRADEOFFS, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        PRODUCER_IDEMPOTENCE, E01_ROOT_CAUSE_PROBE);
    List<CandidateProbe> candidates = List.of(new CandidateProbe(ACKS_MCQ_A2, hypothesis, true));
    HypothesisDiscriminationDiagnosticSelector.Decision decision = new HypothesisDiscriminationDiagnosticSelector.Decision(
        Optional.empty(), sourceAttemptId, 1, 1, 1, 0, null, null, null, false,
        V6FallbackReason.SINGLE_CANDIDATE_TOTAL, List.of(hypothesis), candidates);
    replayInputs.insert(destinationAttemptId, decision);
    return replayInputs.findByDestinationAttempt(destinationAttemptId).orElseThrow().id();
  }

  private UUID insertHeaderDirect(
      UUID id, UUID destinationAttemptId, UUID sourceAttemptId, boolean activated, V6FallbackReason fallbackReason) {
    runtimeJdbc.update("""
        INSERT INTO core.diagnostic_selection_replay_input
          (id, destination_attempt_id, source_attempt_id, snapshot_contract_version,
           relationship_authorized_count, actionable_hypothesis_count, candidate_probe_count,
           participating_hypothesis_count, step1_status, step2_status, activated, fallback_reason)
        VALUES (?, ?, ?, ?, 0, 0, 0, 0, NULL, NULL, ?, ?)
        """, id, destinationAttemptId, sourceAttemptId,
        DiagnosticSelectionReplayInputRepository.SNAPSHOT_CONTRACT_VERSION, activated,
        fallbackReason == null ? null : fallbackReason.name());
    return id;
  }

  private void insertCandidateDirect(
      UUID id, UUID replayInputId, int admissionOrdinal, UUID probeItemVersionId, UUID triggerItemVersionId,
      UUID triggerObjectiveId, String relationshipType, UUID targetObjectiveId, UUID authorizingRelationshipId) {
    runtimeJdbc.update("""
        INSERT INTO core.diagnostic_selection_replay_candidate_probe
          (id, replay_input_id, admission_ordinal, probe_item_version_id, scoreable,
           trigger_item_version_id, trigger_objective_id, relationship_type, target_objective_id,
           authorizing_relationship_id)
        VALUES (?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?)
        """, id, replayInputId, admissionOrdinal, probeItemVersionId, triggerItemVersionId,
        triggerObjectiveId, relationshipType, targetObjectiveId, authorizingRelationshipId);
  }

  private UUID insertDraftRelationship(UUID sourceObjectiveId, UUID targetObjectiveId, String relationshipType) {
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
      replayInputs = new DiagnosticSelectionReplayInputRepository(runtimeJdbc);
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
