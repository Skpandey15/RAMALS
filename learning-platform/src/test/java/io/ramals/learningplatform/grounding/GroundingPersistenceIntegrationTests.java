package io.ramals.learningplatform.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.ai.contract.Usage;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.execution.AiExecutionRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/** Real-PostgreSQL proof for D01, D02, D08, D09 and immutable gate/retrieval audit. */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class GroundingPersistenceIntegrationTests {
  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";
  private static final UUID CURRICULUM =
      UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID SKILL =
      UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ASSESSMENT =
      UUID.fromString("01900000-0000-7000-8000-000000000402");
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
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit CASCADE");
      statement.execute("ALTER DATABASE " + database + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + database + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }
    org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  @Test
  void retrievalIsOwnerScopedReproducibleApprovedOnlyAndAudited() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearnerRepository learners = new LearnerRepository(jdbc);
    UUID learnerA = learners.provisionForSubject("grounding-a").id();
    UUID learnerB = learners.provisionForSubject("grounding-b").id();
    Evidence evidenceA = appendEvidence(jdbc, learnerA, "grounding-a-evidence");
    Evidence evidenceB = appendEvidence(jdbc, learnerB, "grounding-b-evidence");
    appendMastery(jdbc, learnerA);

    Instant asOf = Instant.now();
    Clock fixed = Clock.fixed(asOf, ZoneOffset.UTC);
    JdbcGroundingRetrievalRepository repository = new JdbcGroundingRetrievalRepository(jdbc);
    GroundedContextValidator validator = new GroundedContextValidator(
        JsonMapper.builder().findAndAddModules().build());
    GroundingRetrievalService service = new GroundingRetrievalService(
        repository, new GroundedContextFactory(validator), GroundingRetrievalPolicy.V1, fixed);
    Set<SourceType> requiredSources = Set.of(
        SourceType.LEARNER_EVIDENCE, SourceType.MASTERY, SourceType.SKILL_GRAPH,
        SourceType.CURRICULUM_POLICY, SourceType.APPROVED_CONTENT);

    GroundedContext first = service.retrieve("grounding-a", CURRICULUM, requiredSources);
    GroundedContext second = service.retrieve("grounding-a", CURRICULUM, requiredSources);

    assertThat(second).isEqualTo(first);
    assertThat(first.items()).extracting(GroundedContextItem::evidenceId)
        .contains(evidenceA.id().toString())
        .doesNotContain(evidenceB.id().toString());
    assertThat(first.items().stream()
        .filter(item -> item.sourceType() == SourceType.APPROVED_CONTENT)).isNotEmpty();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM ledger.grounding_retrieval_record WHERE context_id = ?",
        Integer.class, first.contextId())).isEqualTo(1);

    ProposalGroundingRequest proposal = new ProposalGroundingRequest(
        "1.0", "proposal-1", "request-1", "run-1", first.contextId(),
        ProposalType.DIAGNOSTIC, new BigDecimal("0.9000"),
        List.of(new GroundedClaim("KAFKA_BROKER", Set.of(evidenceA.id().toString()))));
    ProposalGroundingService gateService = new ProposalGroundingService(
        new ProposalGroundingGate(validator, new ProposalGroundingPolicy()),
        new JdbcProposalGateDecisionRepository(jdbc), fixed);
    assertThat(gateService.evaluate(proposal, first).accepted()).isTrue();
    assertThat(jdbc.queryForObject(
        "SELECT reason_codes->>0 FROM ledger.proposal_gate_decision WHERE proposal_id = ?",
        String.class, proposal.proposalId())).isEqualTo("ACCEPTED");

    // E10: the retrieved context, execution and deterministic decision are reconstructable with
    // the platform-owned request/run identities. Free-form model output is not part of this join.
    DiagnosticAssessmentRequest diagnosticRequest = new DiagnosticAssessmentRequest(
        "1.0", "interaction-e10", proposal.requestId(),
        new Constraints(InteractionClass.INTERACTIVE_AI, 12_000, null, null, null), first);
    AiProposalEnvelope diagnosticEnvelope = new AiProposalEnvelope(
        "1.0", proposal.proposalId(), AgentType.DIAGNOSTIC, "diagnostic-v1",
        proposal.agentRunId(), "DIAGNOSTIC_ASSESSMENT", "DIAGNOSTIC_ASSESSMENT_PROMPT_V1",
        "ci-fake", "ci-fake", "ci-fake-deterministic-v1", "ROUTE_TABLE_V1",
        TrustLevel.NON_AUTHORITATIVE, "0.9000", List.of(), Map.of("diagnoses", List.of()),
        null, new Usage(10, 0, 5, "0.000000", 3));
    AiExecutionRepository executions = new AiExecutionRepository(
        jdbc, JsonMapper.builder().findAndAddModules().build());
    MDC.put("traceId", "trace-e10");
    try {
      assertThat(executions.commissionDiagnosticAssessment(diagnosticRequest).dispatchAllowed())
          .isTrue();
      executions.insertDiagnosticAssessmentSuccess(
          diagnosticRequest, diagnosticEnvelope, asOf, asOf.plusMillis(3));
    } finally {
      MDC.remove("traceId");
    }
    assertThat(jdbc.queryForMap("""
        SELECT e.interaction_id, e.trace_id, e.request_id, e.agent_run_id, d.context_id
          FROM core.ai_execution e
          JOIN ledger.proposal_gate_decision d
            ON d.request_id = e.request_id AND d.agent_run_id = e.agent_run_id
          JOIN ledger.grounding_retrieval_record g ON g.context_id = d.context_id
         WHERE e.request_id = ?
        """, proposal.requestId()))
        .containsEntry("interaction_id", "interaction-e10")
        .containsEntry("trace_id", "trace-e10")
        .containsEntry("request_id", proposal.requestId())
        .containsEntry("agent_run_id", proposal.agentRunId())
        .containsEntry("context_id", first.contextId());

    int evidenceRowsBefore =
        jdbc.queryForObject("SELECT count(*) FROM ledger.evidence", Integer.class);
    int masteryRowsBefore =
        jdbc.queryForObject("SELECT count(*) FROM ledger.mastery_snapshot", Integer.class);
    ProposalGateDecisionPort.PreParseRejection malformed =
        new ProposalGateDecisionPort.PreParseRejection(
            "malformed-proposal-1",
            "malformed-request-1",
            "malformed-run-1",
            first.contextId(),
            ProposalType.DIAGNOSTIC,
            ProposalGateReason.PROPOSAL_INVALID,
            "PROPOSAL_DIAGNOSES_INVALID",
            new ProposalGateDecisionPort.DecisionCorrelation("interaction-1", "trace-1"));
    JdbcProposalGateDecisionRepository decisionRepository =
        new JdbcProposalGateDecisionRepository(jdbc);

    decisionRepository.appendPreParseRejection(malformed);
    decisionRepository.appendPreParseRejection(malformed);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.proposal_gate_decision WHERE proposal_id = ?",
                Integer.class,
                malformed.proposalId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                """
                SELECT request_id, agent_run_id, context_id, accepted, reason_codes->>0 AS reason,
                       parser_reason_code, interaction_id, trace_id
                FROM ledger.proposal_gate_decision WHERE proposal_id = ?
                """,
                malformed.proposalId()))
        .containsEntry("request_id", "malformed-request-1")
        .containsEntry("agent_run_id", "malformed-run-1")
        .containsEntry("context_id", first.contextId())
        .containsEntry("accepted", false)
        .containsEntry("reason", "PROPOSAL_INVALID")
        .containsEntry("parser_reason_code", "PROPOSAL_DIAGNOSES_INVALID")
        .containsEntry("interaction_id", "interaction-1")
        .containsEntry("trace_id", "trace-1");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger.evidence", Integer.class))
        .isEqualTo(evidenceRowsBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger.mastery_snapshot", Integer.class))
        .isEqualTo(masteryRowsBefore);

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE ledger.proposal_gate_decision SET accepted = false WHERE proposal_id = ?",
        proposal.proposalId())).isInstanceOf(DataAccessException.class);
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
        new BigDecimal("0.8000"), new BigDecimal("0.8000"), 5, 4, lineage);
  }

  private static void appendMastery(JdbcTemplate jdbc, UUID learnerId) {
    MasteryRepository mastery = new MasteryRepository(jdbc);
    mastery.ensureAggregate(learnerId, SKILL, CURRICULUM);
    mastery.insertSnapshot(new MasterySnapshotDraft(
        learnerId, SKILL, CURRICULUM, 1, new BigDecimal("0.8000"), MasteryStatus.MASTERED,
        new BigDecimal("0.8000"), new BigDecimal("0.9000"), new BigDecimal("0.7500"),
        5, 5, "mastery-v1", "confidence-v1", "grounding-integration"));
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
      if (!result.next()) throw new SQLException("PostgreSQL did not return current_database()");
      return result.getString(1);
    }
  }
}
