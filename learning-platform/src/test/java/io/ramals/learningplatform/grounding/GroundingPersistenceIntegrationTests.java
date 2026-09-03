package io.ramals.learningplatform.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiEvaluatedResponseType;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationContext;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationRequest;
import io.ramals.learningplatform.ai.contract.AssessmentRubricDimension;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.ai.contract.Usage;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationDecisionRecord;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationTarget;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionService;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationReplayConflictException;
import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedbackRepository;
import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedbackService;
import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedbackStatus;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Decision;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DeterministicCheck;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DimensionResult;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Outcome;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Reason;
import io.ramals.learningplatform.assessmentevaluation.JdbcAssessmentEvaluationDecisionRepository;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.execution.AiExecutionRepository;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshotDraft;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.math.BigInteger;
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
  private static final UUID ASSESSMENT_ITEM =
      UUID.fromString("01900000-0000-7000-8000-000000000411");
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

  @Test
  void assessmentEvaluationDecisionLinksAnswerExecutionAndGateWithIdempotentReplay() {
    JdbcTemplate jdbc = runtimeJdbc();
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("evaluation-gate-a").id();
    String requestId = "evaluation-request-1";
    String proposalId = "evaluation-proposal-1";
    String agentRunId = "evaluation-run-1";
    String contextId = "evaluation-context-1";
    UUID executionId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    UUID answerResponseId = UUID.randomUUID();

    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at,
           source_refs, source_count)
        VALUES (?, ?, 'EVALUATION_POLICY_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', CAST(? AS jsonb), 2)
        """,
        contextId,
        learnerId,
        "[\"ASSESSMENT:" + answerResponseId + ":answer-v1\","
            + "\"ASSESSMENT:rubric-evidence-1:rubric-v1\"]");
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, agent_version,
           agent_run_id, prompt_template_id, prompt_version, model_route, status,
           request_digest, proposal_digest, started_at, completed_at)
        VALUES (?, ?, 'evaluation-interaction-1', 'ASSESSMENT', '1.0',
                'ASSESSMENT_EVALUATION_AGENT_V1', ?, 'ASSESSMENT_RUBRIC_EVALUATE',
                'ASSESSMENT_RUBRIC_EVALUATE_V1', 'ci-fake', 'SUCCEEDED', ?, ?,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        executionId,
        requestId,
        agentRunId,
        "a".repeat(64),
        "b".repeat(64));
    jdbc.update(
        """
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """,
        attemptId,
        learnerId,
        ASSESSMENT,
        "evaluation-gate-" + attemptId);
    jdbc.update(
        """
        INSERT INTO core.assessment_response
          (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"answer":"B"}'::jsonb, true)
        """,
        answerResponseId,
        attemptId,
        ASSESSMENT_ITEM);
    jdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?",
        attemptId);

    int evidenceBefore = jdbc.queryForObject("SELECT count(*) FROM ledger.evidence", Integer.class);
    int masteryBefore =
        jdbc.queryForObject("SELECT count(*) FROM ledger.mastery_snapshot", Integer.class);
    Decision accepted =
        new Decision(
            Outcome.ACCEPTED,
            List.of(Reason.ACCEPTED),
            Set.of(answerResponseId.toString(), "rubric-evidence-1"),
            List.of(
                new DimensionResult(
                    "accuracy",
                    new BigDecimal("3"),
                    new BigDecimal("4"),
                    "Grounded against the approved accuracy rubric.",
                    Set.of(answerResponseId.toString(), "rubric-evidence-1"))),
            "The answer is mostly accurate.",
            new BigDecimal("0.8500"),
            DeterministicCheck.notApplicable());
    EvaluationDecisionRecord record =
        new EvaluationDecisionRecord(
            proposalId,
            requestId,
            agentRunId,
            contextId,
            answerResponseId.toString(),
            "answer-v1",
            "rubric-v1",
            "evaluation-interaction-1",
            "evaluation-trace-1",
            accepted,
            null,
            new EvaluationTarget(learnerId, SKILL, CURRICULUM, attemptId, ASSESSMENT));
    JdbcAssessmentEvaluationDecisionRepository repository =
        new JdbcAssessmentEvaluationDecisionRepository(jdbc);

    repository.append(record);
    repository.append(record);
    EvaluationDecisionRecord differentTraceReplay =
        new EvaluationDecisionRecord(
            record.proposalId(),
            record.requestId(),
            record.agentRunId(),
            record.contextId(),
            record.answerEvidenceId(),
            record.answerVersion(),
            record.rubricVersion(),
            record.interactionId(),
            "evaluation-trace-retry",
            record.decision(),
            record.parserReasonCode(),
            record.target());
    repository.append(differentTraceReplay);
    EvaluationDecisionRecord differentInteractionReplay =
        new EvaluationDecisionRecord(
            record.proposalId(),
            record.requestId(),
            record.agentRunId(),
            record.contextId(),
            record.answerEvidenceId(),
            record.answerVersion(),
            record.rubricVersion(),
            "evaluation-interaction-retry",
            "evaluation-trace-retry-2",
            record.decision(),
            record.parserReasonCode(),
            record.target());
    repository.append(differentInteractionReplay);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.assessment_evaluation_decision WHERE request_id = ?",
                Integer.class,
                requestId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                """
                SELECT decision.request_id, decision.agent_run_id, decision.answer_evidence_id,
                       decision.answer_version, decision.rubric_version, decision.outcome,
                       decision.deterministic_check, execution.id AS execution_id,
                       decision.learner_id, decision.skill_id, decision.curriculum_version_id,
                       decision.attempt_id, decision.assessment_version_id,
                       decision.normalized_score, decision.score_policy_version,
                       decision.context_id
                  FROM ledger.assessment_evaluation_decision decision
                  JOIN core.ai_execution execution ON execution.id = decision.ai_execution_id
                  JOIN ledger.grounding_retrieval_record grounding
                    ON grounding.context_id = decision.context_id
                 WHERE decision.request_id = ?
                """,
                requestId))
        .containsEntry("request_id", requestId)
        .containsEntry("agent_run_id", agentRunId)
        .containsEntry("answer_evidence_id", answerResponseId.toString())
        .containsEntry("answer_version", "answer-v1")
        .containsEntry("rubric_version", "rubric-v1")
        .containsEntry("outcome", "ACCEPTED")
        .containsEntry("deterministic_check", "NOT_APPLICABLE")
        .containsEntry("execution_id", executionId)
        .containsEntry("learner_id", learnerId)
        .containsEntry("skill_id", SKILL)
        .containsEntry("curriculum_version_id", CURRICULUM)
        .containsEntry("attempt_id", attemptId)
        .containsEntry("assessment_version_id", ASSESSMENT)
        .containsEntry("normalized_score", new BigDecimal("0.7500"))
        .containsEntry("score_policy_version", "EVALUATION_SCORE_POLICY_V1")
        .containsEntry("context_id", contextId);
    assertThat(repository.findAcceptedByRequestId(requestId)).get().satisfies(found -> {
      assertThat(found.target()).isEqualTo(
          new EvaluationTarget(learnerId, SKILL, CURRICULUM, attemptId, ASSESSMENT));
      assertThat(found.normalizedScore()).isEqualByComparingTo("0.7500");
      assertThat(found.scorePolicyVersion()).isEqualTo("EVALUATION_SCORE_POLICY_V1");
      assertThat(found.interactionId()).isEqualTo("evaluation-interaction-1");
      assertThat(found.traceId()).isEqualTo("evaluation-trace-1");
    });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger.evidence", Integer.class))
        .isEqualTo(evidenceBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger.mastery_snapshot", Integer.class))
        .isEqualTo(masteryBefore);

    AssessmentFeedbackService feedbackService =
        new AssessmentFeedbackService(new AssessmentFeedbackRepository(jdbc));
    assertThat(feedbackService.latest("evaluation-gate-a").status())
        .isEqualTo(AssessmentFeedbackStatus.EVALUATED);
    assertThat(feedbackService.latest("evaluation-gate-a").approvedFeedback().feedback())
        .isEqualTo("The answer is mostly accurate.");
    new LearnerRepository(jdbc).provisionForSubject("evaluation-gate-b");
    assertThat(feedbackService.latest("evaluation-gate-b").status())
        .isEqualTo(AssessmentFeedbackStatus.UNAVAILABLE);

    Decision conflicting =
        new Decision(
            accepted.outcome(),
            accepted.reasons(),
            accepted.referencedEvidenceIds(),
            accepted.dimensions(),
            "Different feedback under the same request identity.",
            accepted.confidence(),
            accepted.deterministicCheck());
    EvaluationDecisionRecord conflictingReplay =
        new EvaluationDecisionRecord(
            record.proposalId(),
            record.requestId(),
            record.agentRunId(),
            record.contextId(),
            record.answerEvidenceId(),
            record.answerVersion(),
            record.rubricVersion(),
            record.interactionId(),
            record.traceId(),
            conflicting,
            null,
            record.target());
    assertThatThrownBy(() -> repository.append(conflictingReplay))
        .isInstanceOf(AssessmentEvaluationReplayConflictException.class);

    String secondRequestId = "evaluation-request-2";
    String secondAgentRunId = "evaluation-run-2";
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, agent_version,
           agent_run_id, prompt_template_id, prompt_version, model_route, status,
           request_digest, proposal_digest, started_at, completed_at)
        VALUES (?, ?, 'evaluation-interaction-2', 'ASSESSMENT', '1.0',
                'ASSESSMENT_EVALUATION_AGENT_V1', ?, 'ASSESSMENT_RUBRIC_EVALUATE',
                'ASSESSMENT_RUBRIC_EVALUATE_V1', 'ci-fake', 'SUCCEEDED', ?, ?,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        secondRequestId,
        secondAgentRunId,
        "c".repeat(64),
        "d".repeat(64));
    EvaluationDecisionRecord reusedProposalIdentity =
        new EvaluationDecisionRecord(
            record.proposalId(),
            secondRequestId,
            secondAgentRunId,
            record.contextId(),
            record.answerEvidenceId(),
            record.answerVersion(),
            record.rubricVersion(),
            "evaluation-interaction-2",
            "evaluation-trace-2",
            record.decision(),
            record.parserReasonCode(),
            record.target());
    assertThatThrownBy(() -> repository.append(reusedProposalIdentity))
        .isInstanceOf(AssessmentEvaluationReplayConflictException.class)
        .hasMessage("evaluation proposal identity was reused for a different request");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.assessment_evaluation_decision WHERE request_id = ?",
                Integer.class,
                secondRequestId))
        .isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE ledger.assessment_evaluation_decision SET outcome = 'REJECTED' "
                        + "WHERE request_id = ?",
                    requestId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void targetBindingKeepsLegacyWritesCompatibleAndRejectsMismatchedNewTargets() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearnerRepository learners = new LearnerRepository(jdbc);
    UUID legacyLearner = learners.provisionForSubject("evaluation-target-legacy").id();
    UUID mismatchedContextLearner =
        learners.provisionForSubject("evaluation-target-context-owner").id();
    UUID mismatchedTargetLearner =
        learners.provisionForSubject("evaluation-target-mismatch").id();

    String legacyContext = "evaluation-target-legacy-context";
    String legacyRequest = "evaluation-target-legacy-request";
    String legacyRun = "evaluation-target-legacy-run";
    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at,
           source_refs, source_count)
        VALUES (?, ?, 'EVALUATION_POLICY_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', '["answer","rubric"]'::jsonb, 2)
        """,
        legacyContext,
        legacyLearner);
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, agent_version,
           agent_run_id, prompt_template_id, prompt_version, model_route, status,
           request_digest, proposal_digest, started_at, completed_at)
        VALUES (?, ?, 'evaluation-target-legacy-interaction', 'ASSESSMENT', '1.0',
                'ASSESSMENT_EVALUATION_AGENT_V1', ?, 'ASSESSMENT_RUBRIC_EVALUATE',
                'ASSESSMENT_RUBRIC_EVALUATE_V1', 'ci-fake', 'SUCCEEDED', ?, ?,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        legacyRequest,
        legacyRun,
        "a".repeat(64),
        "b".repeat(64));

    // This is the V033 image's INSERT shape: it does not mention any V034 column. The rollback
    // contract requires it to remain valid after the new migration is applied.
    jdbc.update(
        """
        INSERT INTO ledger.assessment_evaluation_decision
          (id, proposal_id, request_id, agent_run_id, ai_execution_id, context_id,
           answer_evidence_id, answer_version, rubric_version, outcome, reason_codes,
           referenced_evidence_ids, dimension_results, feedback, confidence,
           deterministic_check, deterministic_reason_code, parser_reason_code,
           policy_version, decision_digest, interaction_id, trace_id)
        SELECT ?, ?, ?, ?, execution.id, ?, 'answer', 'answer-v1', 'rubric-v1', 'ACCEPTED',
               '["ACCEPTED"]'::jsonb, '["answer","rubric"]'::jsonb,
               '[{"dimensionId":"accuracy","score":3,"maxScore":4}]'::jsonb,
               'Legacy accepted decision.', 0.85000000, 'NOT_APPLICABLE', NULL, NULL,
               'EVALUATION_GATE_V1', ?, 'evaluation-target-legacy-interaction', 'trace-legacy'
          FROM core.ai_execution execution
         WHERE execution.request_id = ? AND execution.agent_run_id = ?
        """,
        UUID.randomUUID(),
        "evaluation-target-legacy-proposal",
        legacyRequest,
        legacyRun,
        legacyContext,
        "c".repeat(64),
        legacyRequest,
        legacyRun);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.assessment_evaluation_decision WHERE request_id = ?",
                Integer.class,
                legacyRequest))
        .isEqualTo(1);

    String mismatchedContext = "evaluation-target-mismatch-context";
    String mismatchedRequest = "evaluation-target-mismatch-request";
    String mismatchedRun = "evaluation-target-mismatch-run";
    UUID mismatchedAttempt = UUID.randomUUID();
    UUID mismatchedExecution = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at,
           source_refs, source_count)
        VALUES (?, ?, 'EVALUATION_POLICY_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', '["answer","rubric"]'::jsonb, 2)
        """,
        mismatchedContext,
        mismatchedContextLearner);
    jdbc.update(
        """
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'COMPLETED', ?)
        """,
        mismatchedAttempt,
        mismatchedTargetLearner,
        ASSESSMENT,
        "evaluation-target-mismatch-" + mismatchedAttempt);
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, agent_version,
           agent_run_id, prompt_template_id, prompt_version, model_route, status,
           request_digest, proposal_digest, started_at, completed_at)
        VALUES (?, ?, 'evaluation-target-mismatch-interaction', 'ASSESSMENT', '1.0',
                'ASSESSMENT_EVALUATION_AGENT_V1', ?, 'ASSESSMENT_RUBRIC_EVALUATE',
                'ASSESSMENT_RUBRIC_EVALUATE_V1', 'ci-fake', 'SUCCEEDED', ?, ?,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        mismatchedExecution,
        mismatchedRequest,
        mismatchedRun,
        "d".repeat(64),
        "e".repeat(64));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO ledger.assessment_evaluation_decision
                      (id, proposal_id, request_id, agent_run_id, ai_execution_id, context_id,
                       answer_evidence_id, answer_version, rubric_version, outcome, reason_codes,
                       referenced_evidence_ids, dimension_results, feedback, confidence,
                       deterministic_check, deterministic_reason_code, parser_reason_code,
                       policy_version, decision_digest, interaction_id, trace_id,
                       learner_id, skill_id, curriculum_version_id, attempt_id,
                       assessment_version_id, normalized_score, score_policy_version)
                    VALUES (?, ?, ?, ?, ?, ?, 'answer', 'answer-v1', 'rubric-v1', 'ACCEPTED',
                            '["ACCEPTED"]'::jsonb, '["answer","rubric"]'::jsonb,
                            '[{"dimensionId":"accuracy","score":3,"maxScore":4}]'::jsonb,
                            'Mismatched target.', 0.85000000, 'NOT_APPLICABLE', NULL, NULL,
                            'EVALUATION_GATE_V1', ?, 'evaluation-target-mismatch-interaction',
                            'trace-mismatch', ?, ?, ?, ?, ?, 0.7500,
                            'EVALUATION_SCORE_POLICY_V1')
                    """,
                    UUID.randomUUID(),
                    "evaluation-target-mismatch-proposal",
                    mismatchedRequest,
                    mismatchedRun,
                    mismatchedExecution,
                    mismatchedContext,
                    "f".repeat(64),
                    mismatchedTargetLearner,
                    SKILL,
                    CURRICULUM,
                    mismatchedAttempt,
                    ASSESSMENT))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.assessment_evaluation_decision WHERE request_id = ?",
                Integer.class,
        mismatchedRequest))
        .isZero();
  }

  @Test
  void targetValidationUsesExistenceAndBindsAnswerToItsOwningAttempt() {
    JdbcTemplate jdbc = runtimeJdbc();
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject(
        "evaluation-target-authority").id();
    AssessmentTargetFixture assessment = assessmentWithTwoItemsForSkill(jdbc);
    AttemptResponseFixture attemptA = completedAttemptWithResponse(
        jdbc, learnerId, assessment.assessmentVersionId(), assessment.firstItemId(), "target-a");
    AttemptResponseFixture attemptB = completedAttemptWithResponse(
        jdbc, learnerId, assessment.assessmentVersionId(), assessment.secondItemId(), "target-b");
    String answerVersion = "answer-v1";
    String contextId = "evaluation-target-authority-context";
    insertGroundingRecord(jdbc, contextId, learnerId, attemptA.responseId(), answerVersion);

    String requestA = "evaluation-target-authority-a";
    String runA = "evaluation-target-authority-run-a";
    UUID executionA = insertAssessmentExecution(jdbc, requestA, runA,
        "evaluation-target-authority-interaction-a");
    String answerEvidenceId = attemptA.responseId().toString();
    Decision accepted =
        new Decision(
            Outcome.ACCEPTED,
            List.of(Reason.ACCEPTED),
            Set.of(answerEvidenceId),
            List.of(
                new DimensionResult(
                    "accuracy",
                    new BigDecimal("3"),
                    new BigDecimal("4"),
                    "Grounded against the answer.",
                    Set.of(answerEvidenceId))),
            "The answer is mostly accurate.",
            new BigDecimal("0.8500"),
            DeterministicCheck.notApplicable());
    EvaluationTarget targetA = new EvaluationTarget(
        learnerId, SKILL, CURRICULUM, attemptA.attemptId(), assessment.assessmentVersionId());
    EvaluationDecisionRecord recordA =
        new EvaluationDecisionRecord(
            "evaluation-target-authority-proposal-a",
            requestA,
            runA,
            contextId,
            answerEvidenceId,
            answerVersion,
            "rubric-v1",
            "evaluation-target-authority-interaction-a",
            "evaluation-target-authority-trace-a",
            accepted,
            null,
            targetA);
    JdbcAssessmentEvaluationDecisionRepository repository =
        new JdbcAssessmentEvaluationDecisionRepository(jdbc);

    // Two assessment items deliberately share SKILL. The old Java count(*) query saw two matches;
    // the existence query must accept the one valid target.
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM core.assessment_item_version "
            + "WHERE assessment_version_id = ? AND skill_id = ?",
        Integer.class,
        assessment.assessmentVersionId(),
        SKILL)).isEqualTo(2);
    repository.append(recordA);
    assertThat(
            jdbc.queryForMap(
                """
                SELECT decision.attempt_id AS decision_attempt_id,
                       decision.answer_evidence_id,
                       answer_response.id AS answer_response_id,
                       answer_response.attempt_id AS answer_attempt_id
                  FROM ledger.assessment_evaluation_decision decision
                  JOIN core.assessment_response answer_response
                    ON answer_response.id::text = decision.answer_evidence_id
                 WHERE decision.request_id = ?
                """,
                requestA))
        .containsEntry("decision_attempt_id", attemptA.attemptId())
        .containsEntry("answer_evidence_id", answerEvidenceId)
        .containsEntry("answer_response_id", attemptA.responseId())
        .containsEntry("answer_attempt_id", attemptA.attemptId());

    String requestB = "evaluation-target-authority-b";
    String runB = "evaluation-target-authority-run-b";
    insertAssessmentExecution(jdbc, requestB, runB,
        "evaluation-target-authority-interaction-b");
    EvaluationDecisionRecord wrongJavaTarget =
        new EvaluationDecisionRecord(
            "evaluation-target-authority-proposal-b",
            requestB,
            runB,
            contextId,
            answerEvidenceId,
            answerVersion,
            "rubric-v1",
            "evaluation-target-authority-interaction-b",
            "evaluation-target-authority-trace-b",
            accepted,
            null,
            new EvaluationTarget(
                learnerId, SKILL, CURRICULUM, attemptB.attemptId(), assessment.assessmentVersionId()));
    assertThatThrownBy(() -> repository.append(wrongJavaTarget))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("evaluation target does not match authoritative assessment facts");

    // Bypass the Java repository guard: V034 must reject the same cross-attempt substitution at the
    // database boundary. Attempt B is a valid sibling attempt and has its own response; only the
    // answer response A / target attempt B pairing is invalid.
    String requestC = "evaluation-target-authority-c";
    String runC = "evaluation-target-authority-run-c";
    UUID executionC = insertAssessmentExecution(jdbc, requestC, runC,
        "evaluation-target-authority-interaction-c");
    assertThatThrownBy(
            () ->
                insertAcceptedDecisionDirect(
                    jdbc,
                    requestC,
                    runC,
                    executionC,
                    contextId,
                    answerEvidenceId,
                    learnerId,
                    attemptB.attemptId(),
                    assessment.assessmentVersionId()))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.assessment_evaluation_decision WHERE request_id = ?",
                Integer.class,
                requestC))
        .isZero();
  }

  @Test
  void invalidConfidenceValuesCommitAsDurableRejections() {
    JdbcTemplate jdbc = runtimeJdbc();
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("evaluation-invalid-a").id();
    String contextId = "evaluation-invalid-context";
    String answerEvidenceId = "evaluation-invalid-answer";
    String rubricEvidenceId = "evaluation-invalid-rubric";
    Instant now = Instant.now();
    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at,
           source_refs, source_count)
        VALUES (?, ?, 'EVALUATION_POLICY_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', CAST(? AS jsonb), 2)
        """,
        contextId,
        learnerId,
        "[\"evaluation-invalid-answer\",\"evaluation-invalid-rubric\"]");

    GroundedContext grounded =
        new GroundedContext(
            GroundedContext.CONTRACT_VERSION,
            contextId,
            "opaque-evaluation-learner",
            now,
            now.plusSeconds(600),
            EvaluationProposalGate.REQUEST_POLICY,
            List.of(
                new GroundedContextItem(
                    answerEvidenceId,
                    SourceType.ASSESSMENT,
                    "answer-v1",
                    ContextAuthority.AUTHORITATIVE_FACT,
                    "ANSWER_VERSION",
                    "answer-v1",
                    now,
                    null),
                new GroundedContextItem(
                    rubricEvidenceId,
                    SourceType.ASSESSMENT,
                    "rubric-v1",
                    ContextAuthority.AUTHORITATIVE_FACT,
                    "RUBRIC_DIMENSION",
                    "accuracy",
                    now,
                    null)));
    AssessmentEvaluationContext evaluation =
        new AssessmentEvaluationContext(
            AiEvaluatedResponseType.FREE_TEXT,
            "answer-v1",
            "rubric-v1",
            answerEvidenceId,
            "A bounded learner answer.",
            List.of(
                new AssessmentRubricDimension(
                    "accuracy", new BigDecimal("4"), "Approved criterion.", rubricEvidenceId)));
    AssessmentEvaluationDecisionService service =
        new AssessmentEvaluationDecisionService(
            new EvaluationProposalGate(
                new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build())),
            new JdbcAssessmentEvaluationDecisionRepository(jdbc),
            Clock.fixed(now, ZoneOffset.UTC));
    List<Number> invalidValues =
        List.of(new BigDecimal("-1"), new BigDecimal("1.5"), BigInteger.TEN.pow(1_000));

    for (int index = 0; index < invalidValues.size(); index++) {
      String requestId = "evaluation-invalid-request-" + index;
      String proposalId = "evaluation-invalid-proposal-" + index;
      String agentRunId = "evaluation-invalid-run-" + index;
      String interactionId = "evaluation-invalid-interaction-" + index;
      jdbc.update(
          """
          INSERT INTO core.ai_execution
            (id, request_id, interaction_id, agent_type, contract_version, agent_version,
             agent_run_id, prompt_template_id, prompt_version, model_route, status,
             request_digest, proposal_digest, started_at, completed_at)
          VALUES (?, ?, ?, 'ASSESSMENT', '1.0', 'ASSESSMENT_EVALUATION_AGENT_V1', ?,
                  'ASSESSMENT_RUBRIC_EVALUATE', 'ASSESSMENT_RUBRIC_EVALUATE_V1', 'ci-fake',
                  'SUCCEEDED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """,
          UUID.randomUUID(),
          requestId,
          interactionId,
          agentRunId,
          Integer.toString(index).repeat(64),
          Integer.toString(index + 3).repeat(64));
      AssessmentEvaluationRequest request =
          new AssessmentEvaluationRequest(
              AssessmentEvaluationRequest.CONTRACT_VERSION,
              interactionId,
              requestId,
              new Constraints(
                  InteractionClass.ASSESSMENT_PROPOSAL,
                  8_000,
                  1_200,
                  List.of(),
                  EvaluationProposalGate.REQUEST_POLICY),
              evaluation,
              grounded);
      Map<String, Object> payload =
          Map.of(
              "contractVersion", "1.0",
              "proposalId", proposalId,
              "requestId", requestId,
              "agentRunId", agentRunId,
              "answerVersion", "answer-v1",
              "rubricVersion", "rubric-v1",
              "dimensions",
                  List.of(
                      Map.of(
                          "dimensionId", "accuracy",
                          "score", 3,
                          "maxScore", 4,
                          "reason", "Grounded against the approved rubric.",
                          "evidenceIds", List.of(answerEvidenceId, rubricEvidenceId))),
              "feedback", "Explain the answer more precisely.",
              "evidenceIds", List.of(answerEvidenceId),
              "confidence", invalidValues.get(index));
      AiProposalEnvelope envelope =
          new AiProposalEnvelope(
              "1.0",
              proposalId,
              AgentType.ASSESSMENT,
              "ASSESSMENT_EVALUATION_AGENT_V1",
              agentRunId,
              "ASSESSMENT_RUBRIC_EVALUATE",
              "ASSESSMENT_RUBRIC_EVALUATE_V1",
              "ci-fake",
              TrustLevel.NON_AUTHORITATIVE,
              null,
              List.of(),
              payload,
              null,
              null);

      MDC.remove("traceId");
      Decision decision = service.decide(envelope, request, DeterministicCheck.notApplicable());

      assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
      assertThat(decision.confidence()).isNull();
      assertThat(
              jdbc.queryForMap(
                  """
                  SELECT outcome, confidence, parser_reason_code, trace_id
                    FROM ledger.assessment_evaluation_decision
                   WHERE request_id = ?
                  """,
                  requestId))
          .containsEntry("outcome", "REJECTED")
          .containsEntry("confidence", null)
          .containsEntry("parser_reason_code", "EVALUATION_CONFIDENCE_INVALID")
          .containsEntry("trace_id", null);
    }
  }

  private static AssessmentTargetFixture assessmentWithTwoItemsForSkill(JdbcTemplate jdbc) {
    UUID assessmentVersionId = UUID.randomUUID();
    UUID assessmentId =
        jdbc.queryForObject(
            "SELECT assessment_id FROM core.assessment_version WHERE id = ?",
            UUID.class,
            ASSESSMENT);
    jdbc.update(
        """
        INSERT INTO core.assessment_version
          (id, assessment_id, curriculum_version_id, version_code)
        VALUES (?, ?, ?, ?)
        """,
        assessmentVersionId,
        assessmentId,
        CURRICULUM,
        "m2-t14-" + assessmentVersionId.toString().substring(0, 8));
    UUID firstItemId = UUID.randomUUID();
    UUID secondItemId = UUID.randomUUID();
    insertVerifiedAssessmentItem(jdbc, assessmentVersionId, firstItemId,
        "M2_T14_SAME_SKILL_A", 1);
    insertVerifiedAssessmentItem(jdbc, assessmentVersionId, secondItemId,
        "M2_T14_SAME_SKILL_B", 2);
    jdbc.update(
        "UPDATE core.assessment_version SET status = 'PUBLISHED' WHERE id = ?",
        assessmentVersionId);
    return new AssessmentTargetFixture(assessmentVersionId, firstItemId, secondItemId);
  }

  private static void insertVerifiedAssessmentItem(
      JdbcTemplate jdbc,
      UUID assessmentVersionId,
      UUID itemId,
      String itemCode,
      int displayOrder) {
    jdbc.update(
        """
        INSERT INTO core.assessment_item_version
          (id, assessment_version_id, skill_id, item_code, item_type, stem,
           options_jsonb, answer_key_jsonb, difficulty, display_order,
           trust_state, verified_by, verified_at)
        VALUES (?, ?, ?, ?, 'SINGLE_CHOICE', 'Which answer is correct?',
                '[{"id":"A","text":"First"},{"id":"B","text":"Second"}]'::jsonb,
                '{"correct":["B"]}'::jsonb, 'FOUNDATIONAL', ?,
                'VERIFIED_CONTENT', 'm2-t14-postgres-test', CURRENT_TIMESTAMP)
        """,
        itemId,
        assessmentVersionId,
        SKILL,
        itemCode,
        displayOrder);
  }

  private static AttemptResponseFixture completedAttemptWithResponse(
      JdbcTemplate jdbc,
      UUID learnerId,
      UUID assessmentVersionId,
      UUID itemId,
      String label) {
    UUID attemptId = UUID.randomUUID();
    UUID responseId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """,
        attemptId,
        learnerId,
        assessmentVersionId,
        "m2-t14-" + label + "-" + attemptId);
    jdbc.update(
        """
        INSERT INTO core.assessment_response
          (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, '{"answer":"B"}'::jsonb, true)
        """,
        responseId,
        attemptId,
        itemId);
    jdbc.update(
        "UPDATE core.assessment_attempt SET status = 'COMPLETED' WHERE id = ?",
        attemptId);
    return new AttemptResponseFixture(attemptId, responseId);
  }

  private static void insertGroundingRecord(
      JdbcTemplate jdbc, String contextId, UUID learnerId, UUID answerResponseId,
      String answerVersion) {
    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at,
           source_refs, source_count)
        VALUES (?, ?, 'EVALUATION_POLICY_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', CAST(? AS jsonb), 1)
        """,
        contextId,
        learnerId,
        "[\"ASSESSMENT:" + answerResponseId + ":" + answerVersion + "\"]");
  }

  private static UUID insertAssessmentExecution(
      JdbcTemplate jdbc, String requestId, String agentRunId, String interactionId) {
    UUID executionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, agent_version,
           agent_run_id, prompt_template_id, prompt_version, model_route, status,
           request_digest, proposal_digest, started_at, completed_at)
        VALUES (?, ?, ?, 'ASSESSMENT', '1.0', 'ASSESSMENT_EVALUATION_AGENT_V1', ?,
                'ASSESSMENT_RUBRIC_EVALUATE', 'ASSESSMENT_RUBRIC_EVALUATE_V1', 'ci-fake',
                'SUCCEEDED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        executionId,
        requestId,
        interactionId,
        agentRunId,
        "a".repeat(64),
        "b".repeat(64));
    return executionId;
  }

  private static void insertAcceptedDecisionDirect(
      JdbcTemplate jdbc,
      String requestId,
      String agentRunId,
      UUID executionId,
      String contextId,
      String answerEvidenceId,
      UUID learnerId,
      UUID attemptId,
      UUID assessmentVersionId) {
    jdbc.update(
        """
        INSERT INTO ledger.assessment_evaluation_decision
          (id, proposal_id, request_id, agent_run_id, ai_execution_id, context_id,
           answer_evidence_id, answer_version, rubric_version, outcome, reason_codes,
           referenced_evidence_ids, dimension_results, feedback, confidence, deterministic_check,
           policy_version, decision_digest, interaction_id, trace_id,
           learner_id, skill_id, curriculum_version_id, attempt_id, assessment_version_id,
           normalized_score, score_policy_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'answer-v1', 'rubric-v1', 'ACCEPTED',
                CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), 'Feedback.', 0.85,
                'NOT_APPLICABLE', 'EVALUATION_GATE_V1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        "p-" + requestId,
        requestId,
        agentRunId,
        executionId,
        contextId,
        answerEvidenceId,
        "[\"ACCEPTED\"]",
        "[\"" + answerEvidenceId + "\"]",
        "[{\"dimensionId\":\"accuracy\",\"score\":3,\"maxScore\":4}]",
        "c".repeat(64),
        "interaction-" + requestId,
        "trace-" + requestId,
        learnerId,
        SKILL,
        CURRICULUM,
        attemptId,
        assessmentVersionId,
        new BigDecimal("0.7500"),
        "EVALUATION_SCORE_POLICY_V1");
  }

  private record AssessmentTargetFixture(
      UUID assessmentVersionId, UUID firstItemId, UUID secondItemId) {}

  private record AttemptResponseFixture(UUID attemptId, UUID responseId) {}

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
        "grounding-integration"));
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
