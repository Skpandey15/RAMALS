package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.CurriculumGraphValidator;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshotDraft;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * DIAGNOSTIC_SELECTION_V5 (M2-ADR-025) against real PostgreSQL and the real, already-seeded KAFKA v2
 * curriculum/bank and #251's real seeded relationships (V054) -- no invented content anywhere in
 * this class. This class publishes the real v2 assessment version and declares it V5, in only this
 * class's disposable schema, the same accepted pattern
 * {@code AdaptiveDiagnosticSelectionPersistenceIntegrationTests} already established for V2.
 *
 * <p>Source attempts are inserted directly as completed fixtures (an attempt this class did not
 * create through {@code DiagnosticService}), the same way earlier fixture classes in this suite
 * insert state their own service under test does not write. The attempt actually under test is
 * always created through {@code DiagnosticService.createAttempt}, never bypassed.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class HypothesisDrivenProbeSelectionPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID ASSESSMENT_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID CURRICULUM_V2 = UUID.fromString("01900000-0000-7000-8000-000000000004");

  // Real skill ids.
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ACKS_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000107");
  private static final UUID TOPIC_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000102");
  private static final UUID CONSUMER_GROUPS_SKILL =
      UUID.fromString("01900000-0000-7000-8000-000000000109");

  // Real item ids (all VERIFIED_CONTENT, from V049), tagged per V052's real mapping.
  private static final UUID ACKS_MCQ_I2 = UUID.fromString("01900000-0000-7000-8000-000000000624"); // d11
  private static final UUID ACKS_MCQ_A1 = UUID.fromString("01900000-0000-7000-8000-000000000625"); // d11
  // d12, PRODUCER_IDEMPOTENCE's only item -- authored ADVANCED, so serving it needs a real mastery
  // snapshot reaching that band; on bare noEvidence() (FOUNDATIONAL), V2's own band rule correctly
  // excludes it, exactly as it would for any other ADVANCED item.
  private static final UUID ACKS_MCQ_A2 = UUID.fromString("01900000-0000-7000-8000-000000000626");
  private static final UUID TOPIC_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000608"); // d04
  private static final UUID TOPIC_MCQ_I1 = UUID.fromString("01900000-0000-7000-8000-000000000609"); // d04
  private static final UUID CGROUP_FILL_F = UUID.fromString("01900000-0000-7000-8000-000000000634"); // d13
  private static final UUID CGROUP_MCQ_F = UUID.fromString("01900000-0000-7000-8000-000000000629"); // d13

  private static String databaseUrl;
  private ProbeProvenanceRepository probeProvenanceRepository;
  private LearnerRepository learners;
  private MasteryRepository masteryRepository;
  private DiagnosticService diagnostics;
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

    // Publishes the real v2 assessment version and declares it V5 -- in only this class's
    // disposable schema, the same accepted pattern AdaptiveDiagnosticSelectionPersistenceIntegrationTests
    // already established for V2. published_at is set to now, later than v1's, so
    // findPublishedDiagnostic resolves v2.
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("""
          UPDATE core.assessment_version
          SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
              selection_policy_version = 'DIAGNOSTIC_SELECTION_V5'
          WHERE id = '01900000-0000-7000-8000-000000000403'
          """);
    }
  }

  // -------------------------------------------------------------------------------------------
  // The flagship end-to-end flow: a real previous-attempt miss prioritizes a real probe in the
  // next attempt, with provenance persisted and fully readable back.
  // -------------------------------------------------------------------------------------------

  @Test
  void aPreviousAttemptMissPrioritizesTheResolvedProbeInTheNextAttemptWithProvenance() {
    wire();
    Learner learner = learners.provisionForSubject("v5-probe-flow");
    // ACKS_MCQ_A2 is authored ADVANCED; a real snapshot reaching that band is what makes it a
    // genuinely servable candidate rather than one V2's own band rule would exclude regardless of
    // what V5 resolves -- the same rule proven deliberately absent in v5-prerequisite-cap below.
    // KAFKA_PRODUCER_ACKS's own real curriculum prerequisite is KAFKA_BROKER (V003); it must also
    // be secured, or V3 caps ACKS back to FOUNDATIONAL regardless of ACKS's own evidence.
    snapshot(learner.id(), BROKER_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learner.id(), ACKS_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    UUID sourceAttemptId = completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("v5-probe-flow", "KAFKA", "key-2");

    assertThat(attemptPolicy(creation.attempt().id())).isEqualTo(
        HypothesisDrivenProbeDiagnosticSelector.SELECTION_POLICY_VERSION);
    List<Object[]> probeRows = itemRows(creation.attempt().id(), ACKS_MCQ_A2);
    assertThat(probeRows).hasSize(1);
    assertThat(probeRows.get(0)[0]).isEqualTo(SelectionReason.HYPOTHESIS_DRIVEN_PROBE.name());

    Optional<ProbeProvenance> provenance =
        probeProvenanceRepository.findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2);
    assertThat(provenance).isPresent();
    assertThat(provenance.get().sourceAttemptId()).isEqualTo(sourceAttemptId);
    assertThat(provenance.get().sourceItemVersionId()).isEqualTo(ACKS_MCQ_A1);
    assertThat(provenance.get().sourceObjectiveId())
        .isEqualTo(UUID.fromString("01900000-0000-7000-8000-000000000d11"));
    assertThat(provenance.get().relationshipType()).isEqualTo(ProbeRelationshipType.ROOT_CAUSE_PROBE);
    assertThat(provenance.get().targetObjectiveId())
        .isEqualTo(UUID.fromString("01900000-0000-7000-8000-000000000d12"));
    assertThat(provenance.get().authorizingRelationshipId())
        .isEqualTo(UUID.fromString("01900000-0000-7000-8000-000000000e01"));
  }

  // -------------------------------------------------------------------------------------------
  // A correct response never raises a hypothesis.
  // -------------------------------------------------------------------------------------------

  @Test
  void aCorrectResponseRaisesNoHypothesisAndNoV5Adjustment() {
    wire();
    Learner learner = learners.provisionForSubject("v5-correct-response");
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, true);

    AttemptCreation creation = diagnostics.createAttempt("v5-correct-response", "KAFKA", "key-2");

    assertThat(reasonsIn(creation.attempt().id()))
        .doesNotContain(SelectionReason.HYPOTHESIS_DRIVEN_PROBE.name());
  }

  // -------------------------------------------------------------------------------------------
  // A relationship whose target has no real content anywhere in the walk is never a fallback to
  // unrelated content -- the miss simply produces no probe.
  // -------------------------------------------------------------------------------------------

  @Test
  void aMissWithNoActionableRelationshipUnderAnyTypeProducesNoProbeAndNoFallback() {
    wire();
    Learner learner = learners.provisionForSubject("v5-no-items-no-fallback");
    // ACKS_MCQ_A2 (d12, PRODUCER_IDEMPOTENCE) is d12's only item -- ROOT_CAUSE_PROBE(d12->c08) has
    // zero items, CONTRADICTION_CHECK from d12 has no published row, PREREQUISITE_VALIDATION from
    // ACKS's prerequisite (BROKER, three required objectives post-H3) is ambiguous, and
    // SAME_OBJECTIVE_CONFIRMATION has no other d12 item to offer. Every type fails.
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A2, false);

    AttemptCreation creation = diagnostics.createAttempt("v5-no-items-no-fallback", "KAFKA", "key-2");

    assertThat(reasonsIn(creation.attempt().id()))
        .doesNotContain(SelectionReason.HYPOTHESIS_DRIVEN_PROBE.name());
  }

  // -------------------------------------------------------------------------------------------
  // An already-exposed candidate is never repeated.
  // -------------------------------------------------------------------------------------------

  @Test
  void aPreviouslyExposedCandidateIsNeverRepeatedAsAProbe() {
    wire();
    Learner learner = learners.provisionForSubject("v5-exposed-no-repeat");
    exposeItem(learner.id(), ACKS_MCQ_A2);
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    AttemptCreation creation = diagnostics.createAttempt("v5-exposed-no-repeat", "KAFKA", "key-3");

    assertThat(itemVersionIdsIn(creation.attempt().id())).doesNotContain(ACKS_MCQ_A2);
  }

  // -------------------------------------------------------------------------------------------
  // Only the single, immediately preceding completed attempt is ever considered -- an older,
  // stale miss is ignored even though it would otherwise have produced a probe of its own.
  // -------------------------------------------------------------------------------------------

  @Test
  void onlyTheImmediatelyPrecedingCompletedAttemptIsConsideredNotAnOlderStaleOne() {
    wire();
    Learner learner = learners.provisionForSubject("v5-only-immediate-preceding");
    snapshot(learner.id(), BROKER_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learner.id(), ACKS_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    // Both ACKS_MCQ_A1 and ACKS_MCQ_I2 are tagged to the same objective (ACKS_DURABILITY_TRADEOFFS,
    // d11), so either miss alone would resolve the identical ROOT_CAUSE_PROBE -> ACKS_MCQ_A2 probe --
    // deliberately, so the only way this test can pass is if the *recorded* source attempt is the
    // newer one, not merely "a probe was found at all".
    UUID staleAttemptId = completedAttemptWithOneResponseAt(learner.id(), ACKS_MCQ_A1, false, "2 days");
    UUID immediatelyPrecedingAttemptId =
        completedAttemptWithOneResponseAt(learner.id(), ACKS_MCQ_I2, false, "1 day");

    AttemptCreation creation =
        diagnostics.createAttempt("v5-only-immediate-preceding", "KAFKA", "key-3");

    List<Object[]> probeItemRows = itemRows(creation.attempt().id(), ACKS_MCQ_A2);
    assertThat(probeItemRows).hasSize(1);
    Optional<ProbeProvenance> provenance =
        probeProvenanceRepository.findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2);
    assertThat(provenance).isPresent();
    assertThat(provenance.get().sourceAttemptId())
        .isEqualTo(immediatelyPrecedingAttemptId)
        .isNotEqualTo(staleAttemptId);
  }

  // -------------------------------------------------------------------------------------------
  // Determinism hardening: two eligible completed attempts with an identical created_at must still
  // resolve to exactly one, chosen by the explicit ORDER BY created_at DESC, id DESC secondary
  // ordering -- never left to whatever order PostgreSQL happens to return tied rows in.
  // -------------------------------------------------------------------------------------------

  @Test
  void tiedCreatedAtAttemptsResolveDeterministicallyByIdNotInsertionOrder() {
    wire();
    Learner learner = learners.provisionForSubject("v5-tied-created-at");
    snapshot(learner.id(), BROKER_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshot(learner.id(), ACKS_SKILL, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));

    // Both ACKS_MCQ_A1 and ACKS_MCQ_I2 are tagged to the same objective (ACKS_DURABILITY_TRADEOFFS,
    // d11), so either miss alone resolves the identical ROOT_CAUSE_PROBE -> ACKS_MCQ_A2 probe --
    // deliberately, so the only way this test can pass is if the *recorded* source attempt is the
    // one the deterministic tie-break selects, not merely "a probe was found at all".
    UUID expectedWinnerId = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    UUID expectedLoserId = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    String sharedCreatedAt = "TIMESTAMPTZ '2026-01-01 00:00:00+00'";
    // Written in reverse of the order the tie-break must resolve them -- the row that must LOSE the
    // tie (the lower id) is inserted first, and the row that must WIN (the higher id) is inserted
    // second. A passing result therefore cannot be explained by "whichever attempt happened to be
    // inserted, or physically stored, last" -- only by the explicit id DESC secondary key.
    completedAttemptWithOneResponseAtExactId(
        expectedLoserId, learner.id(), ACKS_MCQ_I2, false, sharedCreatedAt);
    completedAttemptWithOneResponseAtExactId(
        expectedWinnerId, learner.id(), ACKS_MCQ_A1, false, sharedCreatedAt);

    AttemptCreation creation = diagnostics.createAttempt("v5-tied-created-at", "KAFKA", "key-2");

    Optional<ProbeProvenance> provenance =
        probeProvenanceRepository.findByAttemptAndItem(creation.attempt().id(), ACKS_MCQ_A2);
    assertThat(provenance).isPresent();
    assertThat(provenance.get().sourceAttemptId())
        .isEqualTo(expectedWinnerId)
        .isNotEqualTo(expectedLoserId);
    assertThat(provenance.get().sourceItemVersionId()).isEqualTo(ACKS_MCQ_A1);
  }

  // -------------------------------------------------------------------------------------------
  // V3 interaction: the probe's own band is never allowed to exceed V3's prerequisite cap.
  // -------------------------------------------------------------------------------------------

  @Test
  void v5NeverOverridesV3sPrerequisiteCapOnTheTargetSkill() {
    wire();
    Learner learner = learners.provisionForSubject("v5-prerequisite-cap");
    // KAFKA_BROKER left with no mastery snapshot at all -- unsecured, so V3 caps KAFKA_TOPIC
    // (whose only curriculum prerequisite is KAFKA_BROKER) at FOUNDATIONAL regardless of TOPIC's
    // own evidence.
    snapshot(learner.id(), TOPIC_SKILL, MasteryStatus.DEVELOPING, Set.of(MasteryDifficultyBand.EASY));
    // TOPIC_MCQ_F (FOUNDATIONAL, d04) missed -> SAME_OBJECTIVE_CONFIRMATION's first candidate by
    // display_order is TOPIC_MCQ_I1 (INTERMEDIATE, d04) -- above the FOUNDATIONAL cap.
    completedAttemptWithOneResponse(learner.id(), TOPIC_MCQ_F, false);

    AttemptCreation creation = diagnostics.createAttempt("v5-prerequisite-cap", "KAFKA", "key-2");

    assertThat(itemVersionIdsIn(creation.attempt().id())).doesNotContain(TOPIC_MCQ_I1);
    assertThat(reasonsIn(creation.attempt().id()))
        .doesNotContain(SelectionReason.HYPOTHESIS_DRIVEN_PROBE.name());
  }

  // -------------------------------------------------------------------------------------------
  // V4 precedence: V5 overrides V4's reason on a shared skill, exactly as M2-ADR-025 §5 requires.
  // -------------------------------------------------------------------------------------------

  @Test
  void v5OverridesV4OnASharedSkill() {
    wire();
    Learner learner = learners.provisionForSubject("v5-v4-precedence");
    // KAFKA_CONSUMER_GROUPS shows a real regression -- V4 would otherwise reprioritise it under
    // HYPOTHESIS_CONFIRMATION.
    masteryRepository.ensureAggregate(learner.id(), CONSUMER_GROUPS_SKILL, CURRICULUM_V2);
    snapshotAt(learner.id(), CONSUMER_GROUPS_SKILL, 1, MasteryStatus.MASTERED,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM, MasteryDifficultyBand.HARD));
    snapshotAt(learner.id(), CONSUMER_GROUPS_SKILL, 2, MasteryStatus.DEVELOPING,
        Set.of(MasteryDifficultyBand.EASY));
    // CGROUP_FILL_F (FOUNDATIONAL, d13) missed -> SAME_OBJECTIVE_CONFIRMATION's first candidate by
    // display_order among the same objective's other items is CGROUP_MCQ_F (FOUNDATIONAL, d13).
    completedAttemptWithOneResponse(learner.id(), CGROUP_FILL_F, false);

    AttemptCreation creation = diagnostics.createAttempt("v5-v4-precedence", "KAFKA", "key-2");

    List<Object[]> probeRows = itemRows(creation.attempt().id(), CGROUP_MCQ_F);
    assertThat(probeRows).hasSize(1);
    assertThat(probeRows.get(0)[0]).isEqualTo(SelectionReason.HYPOTHESIS_DRIVEN_PROBE.name());
    assertThat(reasonsIn(creation.attempt().id()))
        .doesNotContain(SelectionReason.HYPOTHESIS_CONFIRMATION.name());
  }

  // -------------------------------------------------------------------------------------------
  // No learner-state mutation beyond the normal assessment-attempt/-item writes every selector
  // already makes.
  // -------------------------------------------------------------------------------------------

  @Test
  void resolvingAndPersistingAProbeNeverWritesAMasterySnapshotItDidNotAlreadyHave() {
    wire();
    Learner learner = learners.provisionForSubject("v5-no-mutation");
    completedAttemptWithOneResponse(learner.id(), ACKS_MCQ_A1, false);

    Integer snapshotCountBefore = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM ledger.mastery_snapshot WHERE learner_id = ?", Integer.class, learner.id());

    diagnostics.createAttempt("v5-no-mutation", "KAFKA", "key-2");

    Integer snapshotCountAfter = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM ledger.mastery_snapshot WHERE learner_id = ?", Integer.class, learner.id());
    assertThat(snapshotCountAfter).isEqualTo(snapshotCountBefore);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID completedAttemptWithOneResponse(UUID learnerId, UUID itemVersionId, boolean isCorrect) {
    return completedAttemptWithOneResponseAt(learnerId, itemVersionId, isCorrect, null);
  }

  private UUID completedAttemptWithOneResponseAt(
      UUID learnerId, UUID itemVersionId, boolean isCorrect, String age) {
    UUID attemptId = UUID.randomUUID();
    String createdAtExpression = age == null
        ? "CURRENT_TIMESTAMP" : "CURRENT_TIMESTAMP - INTERVAL '" + age + "'";
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key, created_at, updated_at)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?, """ + createdAtExpression + ", " + createdAtExpression + """
        )
        """, attemptId, learnerId, ASSESSMENT_V2, "v5-source-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"selected":["A"]}'::jsonb, ?)
        """, UUID.randomUUID(), attemptId, itemVersionId, isCorrect);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
    return attemptId;
  }

  /**
   * Like {@link #completedAttemptWithOneResponseAt} but with an explicit attempt id and an exact,
   * literal {@code created_at} (rather than an offset from {@code CURRENT_TIMESTAMP}) -- what lets a
   * test force two attempts to share the identical timestamp and differ only by id.
   */
  private UUID completedAttemptWithOneResponseAtExactId(
      UUID attemptId, UUID learnerId, UUID itemVersionId, boolean isCorrect, String createdAtLiteral) {
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key, created_at, updated_at)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?, """ + createdAtLiteral + ", " + createdAtLiteral + """
        )
        """, attemptId, learnerId, ASSESSMENT_V2, "v5-source-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_response (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"selected":["A"]}'::jsonb, ?)
        """, UUID.randomUUID(), attemptId, itemVersionId, isCorrect);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
    return attemptId;
  }

  private void exposeItem(UUID learnerId, UUID itemVersionId) {
    // Items may only be selected while the attempt is IN_PROGRESS (core.protect_assessment_attempt_item),
    // so the attempt must be inserted in that state first and completed afterward, the same order
    // completedAttemptWithOneResponseAt already follows.
    UUID attemptId = UUID.randomUUID();
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, attemptId, learnerId, ASSESSMENT_V2, "v5-exposure-fixture-" + attemptId);
    runtimeJdbc.update("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, 1, 'UNSEEN_ITEM')
        """, UUID.randomUUID(), attemptId, itemVersionId);
    runtimeJdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?", attemptId);
  }

  private void snapshot(UUID learnerId, UUID skillId, MasteryStatus status, Set<MasteryDifficultyBand> bands) {
    masteryRepository.ensureAggregate(learnerId, skillId, CURRICULUM_V2);
    snapshotAt(learnerId, skillId, 1, status, bands);
  }

  private void snapshotAt(
      UUID learnerId, UUID skillId, int aggregateVersion, MasteryStatus status,
      Set<MasteryDifficultyBand> bands) {
    masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, CURRICULUM_V2, aggregateVersion,
        new BigDecimal("1.0000"), status, new BigDecimal("0.7500"),
        new BigDecimal("1.0000"), new BigDecimal("0.7500"), 4, 8,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2", "MASTERY_STATUS_POLICY_V2",
        new BigDecimal("1.0000"), bands, "test-fixture"));
  }

  private List<Object[]> itemRows(UUID attemptId, UUID itemVersionId) {
    return runtimeJdbc.query("""
        SELECT selection_reason FROM core.assessment_attempt_item
        WHERE attempt_id = ? AND item_version_id = ?
        """, (result, row) -> new Object[] {result.getString("selection_reason")},
        attemptId, itemVersionId);
  }

  private Set<String> reasonsIn(UUID attemptId) {
    return Set.copyOf(runtimeJdbc.query(
        "SELECT selection_reason FROM core.assessment_attempt_item WHERE attempt_id = ?",
        (result, row) -> result.getString("selection_reason"), attemptId));
  }

  private Set<UUID> itemVersionIdsIn(UUID attemptId) {
    return Set.copyOf(runtimeJdbc.query(
        "SELECT item_version_id FROM core.assessment_attempt_item WHERE attempt_id = ?",
        (result, row) -> result.getObject("item_version_id", UUID.class), attemptId));
  }

  private String attemptPolicy(UUID attemptId) {
    return runtimeJdbc.queryForObject(
        "SELECT selection_policy FROM core.assessment_attempt WHERE id = ?", String.class, attemptId);
  }

  private void wire() {
    if (diagnostics == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      AssessmentRepository assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      io.ramals.learningplatform.learner.LearnerService learnerService =
          new io.ramals.learningplatform.learner.LearnerService(learners);
      CurriculumService curriculumService = new CurriculumService(
          new CurriculumRepository(runtimeJdbc), new CurriculumGraphValidator());
      ProbeRelationshipService probeRelationshipService =
          new ProbeRelationshipService(new ProbeRelationshipRepository(runtimeJdbc), assessments);
      probeProvenanceRepository = new ProbeProvenanceRepository(runtimeJdbc);
      diagnostics = new DiagnosticService(assessments, learnerService,
          new DiagnosticFormSelector(new DiagnosticFormProperties()),
          new AdaptiveDiagnosticSelector(new AdaptiveDiagnosticFormProperties()), masteryRepository,
          curriculumService, probeRelationshipService, probeProvenanceRepository);
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
