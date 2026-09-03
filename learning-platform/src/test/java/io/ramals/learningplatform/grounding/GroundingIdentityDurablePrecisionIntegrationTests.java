package io.ramals.learningplatform.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.execution.AiExecutionRepository;
import io.ramals.learningplatform.execution.DiagnosticCommissionContext;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-PostgreSQL proof that a grounded-context identity survives persistence.
 *
 * <p>This defect lives exactly at the persistence precision boundary, so an in-memory double cannot
 * reproduce it: a fake store hands back the {@link Instant} it was given, nanoseconds and all, and
 * every reconstruction agrees. Only a real {@code timestamptz} column truncates, and only then does
 * the re-derived identity diverge. Every assertion below therefore round-trips through PostgreSQL.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class GroundingIdentityDurablePrecisionIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";
  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ASSESSMENT = UUID.fromString("01900000-0000-7000-8000-000000000402");

  private static final Set<SourceType> REQUIRED_SOURCES = Set.of(
      SourceType.LEARNER_EVIDENCE, SourceType.MASTERY, SourceType.SKILL_GRAPH,
      SourceType.CURRICULUM_POLICY, SourceType.APPROVED_CONTENT);

  private static String databaseUrl;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = required("RAMALS_TEST_POSTGRES_URL");
    String adminUser = required("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, required("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String database = statement.enquoteIdentifier(currentDatabase(statement), true);
      String admin = statement.enquoteIdentifier(adminUser, true);
      statement.execute("""
          DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_migration') THEN
              CREATE ROLE ramals_core_migration LOGIN PASSWORD 'm0-t05-migration-test';
            ELSE ALTER ROLE ramals_core_migration WITH LOGIN PASSWORD 'm0-t05-migration-test';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_runtime') THEN
              CREATE ROLE ramals_core_runtime LOGIN PASSWORD 'm0-t05-runtime-test';
            ELSE ALTER ROLE ramals_core_runtime WITH LOGIN PASSWORD 'm0-t05-runtime-test';
            END IF;
          END $$
          """);
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + admin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + database + " TO "
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

  /**
   * The column that produced the defect. Asserting the declared precision keeps the canonical
   * precision honest: if the schema ever moves, this fails rather than silently disagreeing with
   * {@link DurableInstant#PRECISION}.
   */
  @Test
  void durableTimestampColumnsKeepMicrosecondPrecision() {
    JdbcTemplate jdbc = runtimeJdbc();
    assertThat(DurableInstant.PRECISION).isEqualTo(ChronoUnit.MICROS);
    for (String[] column : new String[][] {
        {"ledger", "grounding_retrieval_record", "as_of"},
        {"ledger", "grounding_retrieval_record", "expires_at"},
        {"core", "ai_execution_dispatch", "context_as_of"}}) {
      assertThat(jdbc.queryForObject("""
          SELECT datetime_precision FROM information_schema.columns
           WHERE table_schema = ? AND table_name = ? AND column_name = ?
          """, Integer.class, column[0], column[1], column[2]))
          .as("%s.%s.%s", column[0], column[1], column[2])
          .isEqualTo(6);
    }
    // The truncation itself, stated as a fact rather than assumed.
    Instant nanosecondResolution = Instant.parse("2026-08-26T06:15:50.751584123Z");
    assertThat(jdbc.queryForObject(
        "SELECT ?::timestamptz", Instant.class, java.sql.Timestamp.from(nanosecondResolution)))
        .isEqualTo(Instant.parse("2026-08-26T06:15:50.751584Z"))
        .isEqualTo(DurableInstant.canonical(nanosecondResolution));
  }

  /**
   * The Phase-2 failure, reduced to its smallest form: create at nanosecond resolution, persist,
   * read back, reconstruct at the read-back timestamp, and require the identity to be equal.
   *
   * <p>Before the canonicalization this assertion fails on the contextId, which is precisely what
   * made a commissioned diagnostic request unrecoverable.
   */
  @Test
  void contextIdentitySurvivesPostgresRoundTripAndReconstruction() {
    JdbcTemplate jdbc = runtimeJdbc();
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("precision-roundtrip").id();
    appendEvidence(jdbc, learnerId, "precision-roundtrip-evidence");
    appendMastery(jdbc, learnerId);

    // A clock that genuinely carries sub-microsecond nanoseconds, as Clock.systemUTC() does on
    // Linux. Without this the defect is invisible.
    Instant nanosecondResolution = Instant.now().truncatedTo(ChronoUnit.MICROS).plusNanos(123);
    assertThat(nanosecondResolution.getNano() % 1_000).isNotZero();
    GroundingRetrievalService service = service(jdbc, nanosecondResolution);

    GroundedContext original = service.retrieve("precision-roundtrip", CURRICULUM, REQUIRED_SOURCES);

    // 6: the canonical asOf is exactly what the store holds -- no information was dropped on write.
    Instant persistedAsOf = jdbc.queryForObject(
        "SELECT as_of FROM ledger.grounding_retrieval_record WHERE context_id = ?",
        Instant.class, original.contextId());
    assertThat(original.asOf()).isEqualTo(persistedAsOf);
    assertThat(original.asOf()).isEqualTo(DurableInstant.canonical(nanosecondResolution));
    Instant persistedExpiresAt = jdbc.queryForObject(
        "SELECT expires_at FROM ledger.grounding_retrieval_record WHERE context_id = ?",
        Instant.class, original.contextId());
    assertThat(original.expiresAt()).isEqualTo(persistedExpiresAt);

    // 4 and 5: reconstruct from the value the database returned, not the one held in memory.
    GroundedContext reconstructed =
        service.retrieveAt("precision-roundtrip", CURRICULUM, REQUIRED_SOURCES, persistedAsOf);
    assertThat(reconstructed.contextId()).isEqualTo(original.contextId());
    assertThat(reconstructed.asOf()).isEqualTo(original.asOf());
    assertThat(reconstructed).isEqualTo(original);

    // Reconstruction must not append a second, divergent audit row.
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM ledger.grounding_retrieval_record WHERE learner_id = ?",
        Integer.class, learnerId)).isEqualTo(1);
  }

  /** 7 and 8: identity is a function of the canonical inputs, and stays sensitive to real change. */
  @Test
  void identityIsStableForEqualInputsAndDistinctForDifferentCanonicalInstants() {
    JdbcTemplate jdbc = runtimeJdbc();
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("precision-stability").id();
    appendEvidence(jdbc, learnerId, "precision-stability-evidence");
    appendMastery(jdbc, learnerId);

    Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);

    // Two instants that differ only below the durable precision are the same durable identity.
    GroundedContext first = service(jdbc, base.plusNanos(1))
        .retrieve("precision-stability", CURRICULUM, REQUIRED_SOURCES);
    GroundedContext sameMicrosecond = service(jdbc, base.plusNanos(999))
        .retrieve("precision-stability", CURRICULUM, REQUIRED_SOURCES);
    assertThat(sameMicrosecond.contextId()).isEqualTo(first.contextId());

    // A difference the store can actually represent must still change the identity.
    GroundedContext laterMicrosecond = service(jdbc, base.plus(1, ChronoUnit.MICROS))
        .retrieve("precision-stability", CURRICULUM, REQUIRED_SOURCES);
    assertThat(laterMicrosecond.contextId()).isNotEqualTo(first.contextId());
    assertThat(laterMicrosecond.asOf()).isNotEqualTo(first.asOf());
  }

  /**
   * 10: the Phase-2 round trip end to end -- commission the diagnostic request, read the commission
   * back the way a replacement worker does, and re-ground at the recovered timestamp.
   *
   * <p>This is the assertion that corresponds to the observed production failure: worker B recovered
   * an ownerless commission, called {@code retrieveAt(prior.asOf())}, and got a different contextId,
   * so {@code AI_EXECUTION_COMMISSION_CONTEXT_MISMATCH} rejected it until attempts exhausted. The
   * guard is unchanged here and still compares exactly; what changed is that the reconstruction now
   * genuinely matches.
   */
  @Test
  void recoveredDiagnosticCommissionReconstructsTheSameGroundedContext() {
    JdbcTemplate jdbc = runtimeJdbc();
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("precision-commission").id();
    appendEvidence(jdbc, learnerId, "precision-commission-evidence");
    appendMastery(jdbc, learnerId);

    Instant nanosecondResolution = Instant.now().truncatedTo(ChronoUnit.MICROS).plusNanos(457);
    GroundingRetrievalService service = service(jdbc, nanosecondResolution);
    GroundedContext committed =
        service.retrieve("precision-commission", CURRICULUM, REQUIRED_SOURCES);

    String requestId = "wf-diag-" + UUID.randomUUID();
    AiExecutionRepository executions = new AiExecutionRepository(
        jdbc, JsonMapper.builder().findAndAddModules().build());
    DiagnosticAssessmentRequest request = new DiagnosticAssessmentRequest(
        DiagnosticAssessmentRequest.CONTRACT_VERSION, "interaction-" + UUID.randomUUID(), requestId,
        new Constraints(InteractionClass.INTERACTIVE_AI, 12_000, null, null, null), committed);
    assertThat(executions.commissionDiagnosticAssessment(request).dispatchAllowed()).isTrue();

    // Exactly what worker B does after a natural lease reclaim.
    Optional<DiagnosticCommissionContext> recovered =
        executions.findRecoverableDiagnosticCommission(requestId);
    assertThat(recovered).isPresent();
    DiagnosticCommissionContext prior = recovered.orElseThrow();
    assertThat(prior.contextId()).isEqualTo(committed.contextId());
    assertThat(prior.asOf()).isEqualTo(committed.asOf());

    GroundedContext reconstructed =
        service.retrieveAt("precision-commission", CURRICULUM, REQUIRED_SOURCES, prior.asOf());
    assertThat(reconstructed.contextId())
        .as("a recovered commission must re-ground to the identity it was commissioned with")
        .isEqualTo(prior.contextId());

    // The dispatch row is still ownerless: recovery re-grounds, it does not dispatch.
    assertThat(jdbc.queryForObject(
        "SELECT state FROM core.ai_execution_dispatch WHERE request_id = ?", String.class,
        requestId)).isEqualTo("AVAILABLE");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM core.ai_execution_event WHERE request_id = ? AND event_type = 'STARTED'",
        Integer.class, requestId)).isEqualTo(1);
  }

  /**
   * 9: the commission request digest is a durable identity too, and it embeds the whole grounded
   * context. Re-commissioning with a reconstructed context must produce the same digest, or the
   * reused-requestId conflict check would fire on a legitimate recovery.
   */
  @Test
  void commissionRequestDigestStaysDeterministicAcrossReconstruction() {
    JdbcTemplate jdbc = runtimeJdbc();
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("precision-digest").id();
    appendEvidence(jdbc, learnerId, "precision-digest-evidence");
    appendMastery(jdbc, learnerId);

    Instant nanosecondResolution = Instant.now().truncatedTo(ChronoUnit.MICROS).plusNanos(911);
    GroundingRetrievalService service = service(jdbc, nanosecondResolution);
    GroundedContext committed = service.retrieve("precision-digest", CURRICULUM, REQUIRED_SOURCES);

    String requestId = "wf-diag-" + UUID.randomUUID();
    String interactionId = "interaction-" + UUID.randomUUID();
    AiExecutionRepository executions = new AiExecutionRepository(
        jdbc, JsonMapper.builder().findAndAddModules().build());
    assertThat(executions.commissionDiagnosticAssessment(new DiagnosticAssessmentRequest(
        DiagnosticAssessmentRequest.CONTRACT_VERSION, interactionId, requestId,
        new Constraints(InteractionClass.INTERACTIVE_AI, 12_000, null, null, null), committed))
        .dispatchAllowed()).isTrue();
    String firstDigest = jdbc.queryForObject("""
        SELECT request_digest FROM core.ai_execution_event
         WHERE request_id = ? AND event_type = 'STARTED'
        """, String.class, requestId);

    GroundedContext reconstructed = service.retrieveAt(
        "precision-digest", CURRICULUM, REQUIRED_SOURCES,
        executions.findRecoverableDiagnosticCommission(requestId).orElseThrow().asOf());

    // Re-commissioning is idempotent; it must not raise AiExecutionConflictException, which is what
    // a digest that drifted with timestamp precision would have caused.
    executions.commissionDiagnosticAssessment(new DiagnosticAssessmentRequest(
        DiagnosticAssessmentRequest.CONTRACT_VERSION, interactionId, requestId,
        new Constraints(InteractionClass.INTERACTIVE_AI, 12_000, null, null, null), reconstructed));
    assertThat(jdbc.queryForObject("""
        SELECT request_digest FROM core.ai_execution_event
         WHERE request_id = ? AND event_type = 'STARTED'
        """, String.class, requestId)).isEqualTo(firstDigest);
  }

  private static GroundingRetrievalService service(JdbcTemplate jdbc, Instant now) {
    return new GroundingRetrievalService(
        new JdbcGroundingRetrievalRepository(jdbc),
        new GroundedContextFactory(new GroundedContextValidator(
            JsonMapper.builder().findAndAddModules().build())),
        GroundingRetrievalPolicy.V1,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private static Evidence appendEvidence(JdbcTemplate jdbc, UUID learnerId, String lineage) {
    UUID attempt = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'COMPLETED', ?)
        """, attempt, learnerId, ASSESSMENT, lineage);
    return new EvidenceRepository(jdbc).appendDiagnosticEvidence(
        learnerId, SKILL, attempt, ASSESSMENT, "diagnostic-v1", lineage,
        new BigDecimal("0.8000"), new BigDecimal("0.8000"), 5, 4,
        EvidenceCoverage.none(), lineage);
  }

  private static void appendMastery(JdbcTemplate jdbc, UUID learnerId) {
    MasteryRepository mastery = new MasteryRepository(jdbc);
    mastery.ensureAggregate(learnerId, SKILL, CURRICULUM);
    mastery.insertSnapshot(new MasterySnapshotDraft(
        learnerId, SKILL, CURRICULUM, 1, new BigDecimal("0.8000"), MasteryStatus.MASTERED,
        new BigDecimal("0.8000"), new BigDecimal("0.9000"), new BigDecimal("0.7500"),
        5, 5, "mastery-v1", "confidence-v1", "status-v1", null, Set.of(),
        "precision-integration"));
  }

  private static JdbcTemplate runtimeJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD));
  }

  private static String required(String name) {
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
