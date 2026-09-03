package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.AssessmentRepository;
import io.ramals.learningplatform.assessment.DiagnosticFormProperties;
import io.ramals.learningplatform.assessment.DiagnosticFormSelector;
import io.ramals.learningplatform.assessment.DiagnosticScorerV2;
import io.ramals.learningplatform.assessment.DiagnosticService;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionService;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.recommendation.RecommendationPolicy;
import io.ramals.learningplatform.recommendation.RecommendationRepository;
import io.ramals.learningplatform.recommendation.RecommendationService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * V046 against real PostgreSQL: coverage is recorded where it is claimed to be recorded, cannot be
 * recorded dishonestly, and is read back by the V2 projection as written.
 *
 * <p>The array columns are the reason these have to run against PostgreSQL rather than a mock. A
 * {@code UUID[]} written through {@code createArrayOf} and read back through {@code getArray} is a
 * driver round-trip, and no in-memory double exercises it.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class MasteryCoveragePersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID BROKER_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID TOPIC_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000102");
  private static final UUID ITEM_BROKER = UUID.fromString("01900000-0000-7000-8000-000000000411");
  private static final UUID BROKER_OBJECTIVE =
      UUID.fromString("01900000-0000-7000-8000-000000000301");
  private static final UUID TOPIC_OBJECTIVE =
      UUID.fromString("01900000-0000-7000-8000-000000000302");
  private static final String INTERACTION_ID = "01920000-0000-7000-8000-0000000000f1";

  private static String databaseUrl;
  private AssessmentRepository assessments;
  private LearnerRepository learners;
  private EvidenceRepository evidence;
  private MasteryRepository masteryRepository;
  private MasteryService masteryService;
  private DiagnosticService diagnostics;
  private DiagnosticSubmissionService submissions;
  private TransactionTemplate transactionTemplate;
  private JdbcTemplate runtimeJdbc;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    resetSchemas();
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
  // The item-to-objective tagging, and the trigger that keeps it honest
  // -------------------------------------------------------------------------------------------

  @Test
  void everySeededDiagnosticItemIsTaggedAgainstItsOwnSkillsObjective() {
    wire();
    // Not just "five rows exist": each objective must belong to the skill its item assesses, which
    // is the property objective coverage depends on for its meaning.
    Integer mismatched = runtimeJdbc.queryForObject("""
        SELECT count(*)
        FROM core.assessment_item_objective aio
        JOIN core.assessment_item_version iv ON iv.id = aio.item_version_id
        JOIN core.learning_objective lo ON lo.id = aio.objective_id
        JOIN core.skill_version sv ON sv.id = lo.skill_version_id
        WHERE sv.skill_id <> iv.skill_id
        """, Integer.class);
    Integer tagged = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_item_objective", Integer.class);

    // 5 from V046 (the v1 diagnostic) plus 35 from V049 (the v2 bank, DRAFT but already tagged).
    assertThat(tagged).isEqualTo(40);
    assertThat(mismatched).isZero();
    assertThat(runtimeJdbc.queryForList(
        "SELECT objective_id FROM core.assessment_item_objective WHERE item_version_id = ?",
        UUID.class, ITEM_BROKER)).containsExactly(BROKER_OBJECTIVE);
  }

  @Test
  void anItemCannotBeTaggedAgainstAnotherSkillsObjective() {
    wire();
    // The one way objective coverage could be made to lie: credit a skill for an objective it does
    // not own. ITEM_BROKER assesses KAFKA_BROKER; TOPIC_OBJECTIVE belongs to KAFKA_TOPIC.
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.assessment_item_objective (item_version_id, objective_id) VALUES (?, ?)
        """, ITEM_BROKER, TOPIC_OBJECTIVE))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("but item");
  }

  // -------------------------------------------------------------------------------------------
  // Coverage survives the round trip
  // -------------------------------------------------------------------------------------------

  @Test
  void submissionPersistsObjectiveIdsAndMappedBandsOnTheEvidenceRow() {
    wire();
    UUID learnerId = submitBrokerDiagnostic("v046-roundtrip");

    // Read the raw columns first: this is what PostgreSQL actually holds, before any mapper.
    List<UUID> objectives = runtimeJdbc.queryForObject("""
        SELECT covered_objective_ids FROM ledger.evidence
        WHERE learner_id = ? AND skill_id = ? AND evidence_type = 'DIAGNOSTIC'
        """, (result, row) -> java.util.Arrays.stream((Object[]) result.getArray(1).getArray())
            .map(value -> UUID.fromString(String.valueOf(value))).toList(),
        learnerId, BROKER_SKILL);
    List<String> bands = runtimeJdbc.queryForObject("""
        SELECT covered_difficulty_bands FROM ledger.evidence
        WHERE learner_id = ? AND skill_id = ? AND evidence_type = 'DIAGNOSTIC'
        """, (result, row) -> List.of((String[]) result.getArray(1).getArray()),
        learnerId, BROKER_SKILL);

    assertThat(objectives).containsExactly(BROKER_OBJECTIVE);
    // FOUNDATIONAL was mapped to EASY on the way in. The band vocabulary is what is stored, not
    // the item vocabulary -- that translation is the whole point of AssessmentDifficulty.
    assertThat(bands).containsExactly("EASY");

    // And the repository reads back exactly what was written.
    Evidence recorded = evidence.findByLearnerAndSkill(learnerId, BROKER_SKILL).getFirst();
    assertThat(recorded.coverage().objectiveIds()).containsExactly(BROKER_OBJECTIVE);
    assertThat(recorded.coverage().difficultyBands())
        .containsExactly(MasteryDifficultyBand.EASY);
  }

  @Test
  void theV2ProjectionReadsThePersistedCoverageRatherThanRecomputingIt() {
    wire();
    UUID learnerId = submitBrokerDiagnostic("v046-projection");

    MasterySnapshot snapshot = masteryService
        .latestSnapshot(learnerId, BROKER_SKILL, CURRICULUM).orElseThrow();

    // 0.40*(1/5) + 0.35*(1/1) + 0.15 + 0.10. The 0.35 is the persisted objective id being read
    // back and intersected with what the skill requires -- under V1 this term was always zero.
    assertThat(snapshot.objectiveCoverage()).isEqualByComparingTo("1.0000");
    assertThat(snapshot.evidenceConfidence()).isEqualByComparingTo("0.6800");
    assertThat(snapshot.coveredDifficultyBands()).containsExactly(MasteryDifficultyBand.EASY);
    assertThat(snapshot.confidenceAlgorithmVersion())
        .isEqualTo(EvidenceConfidenceCalculatorV2.ALGORITHM_VERSION);
    assertThat(snapshot.statusPolicyVersion()).isEqualTo(MasteryStatusPolicyV2.POLICY_VERSION);
    // The mastery algorithm is deliberately untouched by this work.
    assertThat(snapshot.algorithmVersion())
        .isEqualTo(WeightedMasteryCalculator.ALGORITHM_VERSION);

    // One item is still one item: KAFKA_BROKER requires five and both bands, so the score speaks
    // but the status does not confirm mastery.
    assertThat(snapshot.masteryScore()).isEqualByComparingTo("1.0000");
    assertThat(snapshot.status()).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void theDatabaseRefusesABandOutsideTheVocabulary() {
    wire();
    UUID learnerId = learners.provisionForSubject("v046-badband").id();
    UUID attemptId = diagnostics.createAttempt("v046-badband", "kafka", "key-1").attempt().id();

    // Fail-closed at the column, not only in the Java that parses it back.
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO ledger.evidence
          (id, learner_id, skill_id, evidence_type, source_type, source_attempt_id, lineage_key,
           observed_score, normalized_score, interaction_id, covered_difficulty_bands)
        VALUES (?, ?, ?, 'DIAGNOSTIC', 'ASSESSMENT_ATTEMPT', ?, 'bad-band', 1, 1, ?,
                ARRAY['SPICY']::VARCHAR(16)[])
        """, UUID.randomUUID(), learnerId, BROKER_SKILL, attemptId, INTERACTION_ID))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_evidence_covered_bands_known");
  }

  // -------------------------------------------------------------------------------------------
  // Legacy evidence
  // -------------------------------------------------------------------------------------------

  @Test
  void evidenceWrittenBeforeV046StaysReadableAndGrantsNoCoverage() {
    wire();
    UUID learnerId = learners.provisionForSubject("v046-legacy").id();
    UUID attemptId = diagnostics.createAttempt("v046-legacy", "kafka", "key-1").attempt().id();

    // Exactly the shape a pre-V046 image writes: the coverage columns are simply absent from the
    // insert, so they are NULL. Nothing is backfilled for such a row, ever.
    for (int i = 0; i < 6; i++) {
      runtimeJdbc.update("""
          INSERT INTO ledger.evidence
            (id, learner_id, skill_id, evidence_type, source_type, source_attempt_id, lineage_key,
             observed_score, normalized_score, items_answered, items_correct, interaction_id)
          VALUES (?, ?, ?, 'DIAGNOSTIC', 'ASSESSMENT_ATTEMPT', ?, ?, 1.0000, 1.0000, 1, 1, ?)
          """, UUID.randomUUID(), learnerId, TOPIC_SKILL, attemptId, "legacy:" + i, INTERACTION_ID);
    }

    List<Evidence> legacy = evidence.findByLearnerAndSkill(learnerId, TOPIC_SKILL);
    assertThat(legacy).hasSize(6);
    // Readable, and honest about what it cannot say.
    assertThat(legacy).allSatisfy(row -> {
      assertThat(row.coverage().objectiveIds()).isEmpty();
      assertThat(row.coverage().difficultyBands()).isEmpty();
      assertThat(row.normalizedScore()).isEqualByComparingTo("1.0000");
    });

    MasterySnapshot snapshot = transactionTemplate.execute(status ->
        masteryService.recompute(learnerId, TOPIC_SKILL, CURRICULUM, INTERACTION_ID));

    // Six perfect observations clear the volume gate, so the score model says MASTERED -- and the
    // policy still withholds it, because rows that recorded no coverage grant none.
    assertThat(snapshot.masteryScore()).isEqualByComparingTo("1.0000");
    assertThat(snapshot.objectiveCoverage()).isEqualByComparingTo("0.0000");
    assertThat(snapshot.coveredDifficultyBands()).isEmpty();
    assertThat(snapshot.status()).isEqualTo(MasteryStatus.DEVELOPING);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  private UUID submitBrokerDiagnostic(String subject) {
    UUID attemptId = diagnostics.createAttempt(subject, "kafka", "key-1").attempt().id();
    UUID learnerId = learners.findBySubject(subject).orElseThrow().id();
    MDC.put("interactionId", INTERACTION_ID);
    try {
      transactionTemplate.execute(status -> submissions.submit(subject, "kafka",
          attemptId.toString(),
          new DiagnosticSubmissionRequest(
              List.of(new ItemResponse(ITEM_BROKER.toString(), List.of("B"))))));
    } finally {
      MDC.remove("interactionId");
    }
    return learnerId;
  }

  private void wire() {
    if (submissions == null) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD);
      runtimeJdbc = new JdbcTemplate(dataSource);
      JsonMapper mapper = JsonMapper.builder().build();
      assessments = new AssessmentRepository(runtimeJdbc, mapper);
      learners = new LearnerRepository(runtimeJdbc);
      evidence = new EvidenceRepository(runtimeJdbc);
      masteryRepository = new MasteryRepository(runtimeJdbc);
      LearnerService learnerService = new LearnerService(learners);
      masteryService = new MasteryService(
          masteryRepository, evidence, new WeightedMasteryCalculator(),
          new EvidenceConfidenceCalculatorV2(), new MasteryStatusPolicyV2());
      diagnostics = new DiagnosticService(assessments, learnerService,
          new DiagnosticFormSelector(new DiagnosticFormProperties()));
      RecommendationService recommendationService = new RecommendationService(
          new RecommendationPolicy(), new RecommendationRepository(runtimeJdbc), learnerService);
      submissions = new DiagnosticSubmissionService(
          assessments, learnerService, new DiagnosticScorerV2(),
          new EvidenceService(evidence), masteryService, recommendationService, mapper);
      transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }
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
