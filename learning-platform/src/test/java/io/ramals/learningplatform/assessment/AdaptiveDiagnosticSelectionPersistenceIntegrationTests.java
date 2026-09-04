package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasterySnapshotDraft;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
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
 * PR-B's adaptive selector against real PostgreSQL and the real V049 Kafka v2 bank: no-repeat
 * exclusion, evidence-driven band escalation, and bank exhaustion, exercised end to end through
 * {@link DiagnosticService#createAttempt}.
 *
 * <p><b>This class publishes the v2 version, and only this class's disposable schema.</b> V049
 * leaves it DRAFT; {@code findPublishedDiagnostic} only ever resolves a PUBLISHED version, so there
 * is no way to exercise the full create-attempt path against v2 without one being published
 * somewhere. Every other test class in this suite runs {@link #migrate} in its own {@code
 * @BeforeAll}, which drops and rebuilds its own schema from scratch, so this class's publish never
 * reaches any other class's tests, v1's own published status, or the migration itself -- V049 on
 * disk is untouched, and whether v2 should really be published is still the joint PR-A+PR-B review
 * the plan calls for, not a decision this test makes.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class AdaptiveDiagnosticSelectionPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID KAFKA_V1 = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID KAFKA_V2 = UUID.fromString("01900000-0000-7000-8000-000000000403");
  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");

  /** The five skills V049 authored content for. */
  private static final Set<String> V2_SKILL_CODES = Set.of(
      "KAFKA_BROKER", "KAFKA_TOPIC", "KAFKA_PARTITION", "KAFKA_PRODUCER_ACKS",
      "KAFKA_CONSUMER_GROUPS");

  /** 1 FOUNDATIONAL SINGLE_CHOICE + 1 FOUNDATIONAL FILL_BLANK per skill, per V049's inventory. */
  private static final int TOTAL_FOUNDATIONAL_ITEMS = V2_SKILL_CODES.size() * 2;

  private static String databaseUrl;
  private AssessmentRepository assessments;
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

    // Test-only, this schema only -- see the class javadoc. V049 leaves this row DRAFT on disk.
    try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("""
          UPDATE core.assessment_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
           WHERE id = '01900000-0000-7000-8000-000000000403'
          """);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Which selector governs the version
  // -------------------------------------------------------------------------------------------

  @Test
  void theKafkaV2VersionDeclaresTheAdaptiveSelectionPolicyAndV1DoesNot() {
    wire();
    assertThat(assessments.findSelectionPolicyVersion(KAFKA_V2))
        .contains(AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION);
    assertThat(assessments.findSelectionPolicyVersion(KAFKA_V1)).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Packet composition and provenance
  // -------------------------------------------------------------------------------------------

  @Test
  void adaptiveAttemptCreationRespectsTheTypedQuotaAndRecordsProvenance() {
    wire();
    AttemptCreation creation =
        diagnostics.createAttempt("pg-adaptive-quota", "kafka", "key-1");

    Object[] row = attemptPolicyRow(creation.attempt().id());
    assertThat(row[0]).isEqualTo(AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION);
    assertThat(row[1]).isEqualTo(AdaptiveDiagnosticSelector.PACKET_POLICY);

    List<DiagnosticItem> presented =
        assessments.findPresentedItems(creation.attempt().id(), KAFKA_V2);
    assertThat(presented).isNotEmpty();
    long singleChoice = presented.stream().filter(i -> "SINGLE_CHOICE".equals(i.itemType())).count();
    long fillBlank = presented.stream().filter(i -> "FILL_BLANK".equals(i.itemType())).count();
    assertThat(singleChoice).isLessThanOrEqualTo(5);
    assertThat(fillBlank).isLessThanOrEqualTo(2);
    assertThat(presented).extracting(DiagnosticItem::skillCode).allMatch(V2_SKILL_CODES::contains);
  }

  // -------------------------------------------------------------------------------------------
  // No repetition, across consecutive attempts, real content
  // -------------------------------------------------------------------------------------------

  @Test
  void noLogicalQuestionRepeatsAcrossConsecutiveAdaptiveAttemptsForTheSameLearner() {
    wire();
    AttemptCreation first = diagnostics.createAttempt("pg-adaptive-norepeat", "kafka", "key-1");
    Set<UUID> firstLogicalIds = logicalIdsOf(first.attempt().id());
    assertThat(firstLogicalIds).isNotEmpty();
    assessments.completeAttempt(first.attempt().id());

    AttemptCreation second = diagnostics.createAttempt("pg-adaptive-norepeat", "kafka", "key-2");
    Set<UUID> secondLogicalIds = logicalIdsOf(second.attempt().id());
    assertThat(secondLogicalIds).isNotEmpty();

    assertThat(secondLogicalIds).doesNotContainAnyElementsOf(firstLogicalIds);
  }

  @Test
  void differentLearnersAreNotConstrainedByEachOthersExposureHistory() {
    wire();
    AttemptCreation learnerA = diagnostics.createAttempt("pg-adaptive-isolation-a", "kafka", "key-1");
    AttemptCreation learnerB = diagnostics.createAttempt("pg-adaptive-isolation-b", "kafka", "key-1");

    // Both are brand-new learners with no history: nothing stops them from being handed the same
    // logical questions, and asserting otherwise would be asserting a coincidence, not a rule. What
    // is a rule -- each learner's own resulting exposure set contains only items presented to that
    // learner -- is asserted directly.
    assertThat(assessments.findLearnerExposedLogicalItemIds(learnerA.attempt().learnerId()))
        .isEqualTo(logicalIdsOf(learnerA.attempt().id()));
    assertThat(assessments.findLearnerExposedLogicalItemIds(learnerB.attempt().learnerId()))
        .isEqualTo(logicalIdsOf(learnerB.attempt().id()));
  }

  // -------------------------------------------------------------------------------------------
  // Bank exhaustion is reported, and never reaches into a band no evidence has earned
  // -------------------------------------------------------------------------------------------

  @Test
  void exhaustionAtTheHeldBandIsReportedAndNeverReachesIntoUnearnedDifficulty() {
    wire();
    String subject = "pg-adaptive-exhaustion";
    Set<UUID> everyLogicalIdSeen = new HashSet<>();
    Set<String> everyDifficultySeen = new HashSet<>();

    int cycle = 0;
    AssessmentBankExhaustedException thrown = null;
    while (cycle < 8 && thrown == null) {
      cycle++;
      try {
        AttemptCreation creation =
            diagnostics.createAttempt(subject, "kafka", "key-" + cycle);
        everyLogicalIdSeen.addAll(logicalIdsOf(creation.attempt().id()));
        everyDifficultySeen.addAll(difficultiesOf(creation.attempt().id()));
        assessments.completeAttempt(creation.attempt().id());
      } catch (AssessmentBankExhaustedException exhausted) {
        thrown = exhausted;
      }
    }

    assertThat(thrown)
        .as("no mastery evidence is ever recorded for this learner, so the decided band never "
            + "leaves FOUNDATIONAL; the FOUNDATIONAL-only content must run out")
        .isNotNull();
    assertThat(thrown.exhaustedSkillCodes()).isNotEmpty().isSubsetOf(V2_SKILL_CODES);

    // The proof that matters: every item ever presented across every cycle was FOUNDATIONAL, and
    // the total never exceeded the bank's FOUNDATIONAL-only capacity -- despite 25 INTERMEDIATE and
    // ADVANCED items sitting unseen in the same pool the whole time.
    assertThat(everyDifficultySeen).containsExactly("FOUNDATIONAL");
    assertThat(everyLogicalIdSeen).hasSizeLessThanOrEqualTo(TOTAL_FOUNDATIONAL_ITEMS);
  }

  // -------------------------------------------------------------------------------------------
  // Evidence-driven escalation: one skill's own signal, verified against real mastery evidence
  // -------------------------------------------------------------------------------------------

  @Test
  void aSkillWithStrongEvidenceAtFoundationalEscalatesToIntermediateInTheNextAttempt() {
    wire();
    Learner learner = learners.provisionForSubject("pg-adaptive-progression");
    masteryRepository.ensureAggregate(learner.id(), BROKER_SKILL, CURRICULUM);
    masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learner.id(), BROKER_SKILL, CURRICULUM, 1,
        new BigDecimal("1.0000"), MasteryStatus.DEVELOPING, new BigDecimal("0.7500"),
        new BigDecimal("1.0000"), new BigDecimal("0.7500"), 4, 8,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2", "MASTERY_STATUS_POLICY_V2",
        new BigDecimal("1.0000"), Set.of(MasteryDifficultyBand.EASY), "test-fixture"));

    AttemptCreation creation =
        diagnostics.createAttempt("pg-adaptive-progression", "kafka", "key-1");

    List<Object[]> brokerRows = brokerItemRows(creation.attempt().id());
    assertThat(brokerRows)
        .as("KAFKA_BROKER has strong evidence at FOUNDATIONAL, so its own pick must be the escalated "
            + "band and reason -- distinct from every other skill's UNSEEN_ITEM baseline pick")
        .isNotEmpty();
    for (Object[] row : brokerRows) {
      assertThat(row[0]).isEqualTo("INTERMEDIATE");
      assertThat(row[1]).isEqualTo("DIFFICULTY_PROGRESSION");
    }
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private Set<UUID> logicalIdsOf(UUID attemptId) {
    return Set.copyOf(runtimeJdbcQuery("""
        SELECT lin.logical_item_id
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_item_lineage lin ON lin.item_version_id = ai.item_version_id
        WHERE ai.attempt_id = ?
        """, (result, row) -> (UUID) result.getObject("logical_item_id"), attemptId));
  }

  private Set<String> difficultiesOf(UUID attemptId) {
    return Set.copyOf(runtimeJdbcQuery("""
        SELECT DISTINCT iv.difficulty
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_item_version iv ON iv.id = ai.item_version_id
        WHERE ai.attempt_id = ?
        """, (result, row) -> result.getString("difficulty"), attemptId));
  }

  private List<Object[]> brokerItemRows(UUID attemptId) {
    return runtimeJdbcQuery("""
        SELECT iv.difficulty, ai.selection_reason
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_item_version iv ON iv.id = ai.item_version_id
        WHERE ai.attempt_id = ? AND iv.skill_id = ?
        """, (result, row) -> new Object[] {result.getString("difficulty"), result.getString("selection_reason")},
        attemptId, BROKER_SKILL);
  }

  private Object[] attemptPolicyRow(UUID attemptId) {
    List<Object[]> rows = runtimeJdbcQuery(
        "SELECT selection_policy, packet_policy FROM core.assessment_attempt WHERE id = ?",
        (result, row) -> new Object[] {result.getString("selection_policy"), result.getString("packet_policy")},
        attemptId);
    return rows.getFirst();
  }

  private <T> List<T> runtimeJdbcQuery(
      String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    return runtimeJdbc.query(sql, mapper, args);
  }

  private void wire() {
    if (assessments == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      diagnostics = new DiagnosticService(assessments, learnerService,
          new DiagnosticFormSelector(new DiagnosticFormProperties()),
          new AdaptiveDiagnosticSelector(new AdaptiveDiagnosticFormProperties()), masteryRepository);
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
