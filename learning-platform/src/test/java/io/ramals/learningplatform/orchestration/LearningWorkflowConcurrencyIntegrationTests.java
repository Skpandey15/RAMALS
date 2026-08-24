package io.ramals.learningplatform.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasterySnapshotDraft;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Status;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepClaim;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepRun;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real-PostgreSQL proof for the M2-T14 step claim (G04-G08 concurrency semantics).
 *
 * <p>This file is the authority for the claim. The orchestrator's unit tests use an in-memory store
 * that mirrors these predicates, and a mirror is exactly the kind of thing that can quietly stop
 * matching. Atomicity, uniqueness and compare-and-set are properties of the database, so they are
 * asserted against one.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class LearningWorkflowConcurrencyIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";
  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID ASSESSMENT = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static String databaseUrl;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = required("RAMALS_TEST_POSTGRES_URL");
    String adminUser = required("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection =
            DriverManager.getConnection(
                databaseUrl, adminUser, required("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String database = statement.enquoteIdentifier(currentDatabase(statement), true);
      String admin = statement.enquoteIdentifier(adminUser, true);
      statement.execute(
          """
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
      statement.execute(
          "GRANT CONNECT ON DATABASE " + database + " TO " + MIGRATION_USER + ", " + RUNTIME_USER);
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
  void twoWorkersRacingForTheSameStepProduceExactlyOneClaim() throws Exception {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "claim-race");

    // Two independent connections, released together, so the race is real rather than sequential.
    List<Optional<StepClaim>> outcomes =
        race(
            () -> new LearningWorkflowRepository(runtimeJdbc())
                .claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE),
            () -> new LearningWorkflowRepository(runtimeJdbc())
                .claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE));

    assertThat(outcomes.stream().filter(Optional::isPresent).count())
        .as("exactly one worker may own a step")
        .isEqualTo(1);
    StepRun step = runs.step(run.id(), Step.first()).orElseThrow();
    assertThat(step.status()).isEqualTo(StepStatus.RUNNING);
    assertThat(step.attemptCount())
        .as("a losing caller must not spend an attempt")
        .isEqualTo(1);
  }

  @Test
  void aStaleWorkerCannotCompleteAStepAfterTheRunWasCancelled() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "stale-cancel");

    StepClaim claim =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE)
            .orElseThrow();
    // The remote call is notionally in flight here.
    runs.finishRun(run.id(), Status.CANCELLED, "CANCELLED_BY_OPERATOR");
    runs.markCurrentStepTerminal(
        run.id(), Step.first(), StepStatus.CANCELLED, "CANCELLED_BY_OPERATOR");

    assertThat(runs.finishClaimedStep(claim, StepStatus.COMPLETED, null, "req", null)).isFalse();
    assertThat(runs.retryClaimedStep(claim, "STEP_EXECUTION_FAILED")).isFalse();
    assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(Status.CANCELLED);
    assertThat(runs.step(run.id(), Step.first()).orElseThrow().status())
        .isEqualTo(StepStatus.CANCELLED);
  }

  @Test
  void aStaleWorkerCannotCompleteAStepAfterTheRunTimedOut() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "stale-timeout");

    StepClaim claim =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE)
            .orElseThrow();
    runs.finishRun(run.id(), Status.TIMED_OUT, "RUN_DEADLINE_EXCEEDED");
    runs.markCurrentStepTerminal(
        run.id(), Step.first(), StepStatus.TIMED_OUT, "RUN_DEADLINE_EXCEEDED");

    assertThat(runs.finishClaimedStep(claim, StepStatus.COMPLETED, null, "req", null)).isFalse();
    assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(Status.TIMED_OUT);
    assertThat(runs.step(run.id(), Step.first()).orElseThrow().status())
        .isEqualTo(StepStatus.TIMED_OUT);
  }

  @Test
  void attemptCountRisesOncePerSuccessfulClaimAndStaysBounded() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "bounded-retry");

    for (int attempt = 1; attempt <= LearningWorkflowPolicy.MAX_STEP_ATTEMPTS; attempt++) {
      StepClaim claim =
          runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE)
              .orElseThrow();
      assertThat(claim.attemptCount()).isEqualTo(attempt);
      // A competing caller during the same attempt must not increment anything.
      assertThat(runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE))
          .isEmpty();
      assertThat(runs.retryClaimedStep(claim, "STEP_EXECUTION_FAILED")).isTrue();
    }

    assertThat(runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE))
        .as("the ceiling is part of the claim predicate")
        .isEmpty();
    assertThat(runs.step(run.id(), Step.first()).orElseThrow().attemptCount())
        .isEqualTo(LearningWorkflowPolicy.MAX_STEP_ATTEMPTS);
  }

  @Test
  void aClaimIsRefusedOnceTheRunHasMovedToAnotherStep() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "cursor-moved");

    runs.advanceTo(run.id(), Step.RECOMPUTE_MASTERY);

    assertThat(runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE))
        .as("a worker holding a stale cursor must not start an earlier step")
        .isEmpty();
  }

  @Test
  void aSkippedStepIsRecordedWithNoAttemptAndNeverOverwritesARealOutcome() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "skip-audit");

    runs.markSkipped(run.id(), Step.DIAGNOSE, "DIAGNOSIS_NOT_REQUIRED");
    StepRun skipped = runs.step(run.id(), Step.DIAGNOSE).orElseThrow();
    assertThat(skipped.status()).isEqualTo(StepStatus.SKIPPED);
    assertThat(skipped.attemptCount()).isZero();
    assertThat(skipped.executionToken()).isNull();

    // A real outcome already present must survive a later skip attempt.
    StepClaim claim =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE)
            .orElseThrow();
    runs.finishClaimedStep(claim, StepStatus.COMPLETED, null, null, null);
    runs.markSkipped(run.id(), Step.first(), "DIAGNOSIS_NOT_REQUIRED");

    StepRun real = runs.step(run.id(), Step.first()).orElseThrow();
    assertThat(real.status()).isEqualTo(StepStatus.COMPLETED);
    assertThat(real.attemptCount()).isEqualTo(1);
  }

  @Test
  void cancellingAClaimedStepKeepsItsRealAttemptCount() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "cancel-attempts");

    StepClaim first =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE)
            .orElseThrow();
    runs.retryClaimedStep(first, "STEP_EXECUTION_FAILED");
    runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE).orElseThrow();

    runs.markCurrentStepTerminal(
        run.id(), Step.first(), StepStatus.CANCELLED, "CANCELLED_BY_OPERATOR");

    assertThat(runs.step(run.id(), Step.first()).orElseThrow().attemptCount())
        .as("cancellation must not manufacture or erase an attempt")
        .isEqualTo(2);
  }

  @Test
  void theAdaptationStepRequestIdJoinsToTheDurableOutboxRow() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("wf-correlation").id();
    Run run = startRun(jdbc, runs, "correlation", learnerId);

    // Stand in for the recommendation transaction: one decision record and the adaptation work it
    // enqueues, with the request id the repository actually derives.
    MasterySnapshot snapshot = insertSnapshot(jdbc, learnerId);
    UUID decisionId = insertDecisionRecord(jdbc, learnerId, snapshot);
    String adaptationRequestId =
        new io.ramals.learningplatform.recommendation.RecommendationRepository(jdbc)
            .appendAdaptationWork(decisionRecord(jdbc, decisionId))
            .requestId();

    StepClaim claim =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE)
            .orElseThrow();
    runs.finishClaimedStep(claim, StepStatus.COMPLETED, null, adaptationRequestId, null);

    // The assertion the review asked for: a real join, not a non-blank check.
    Integer joined =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM core.learning_workflow_step step
              JOIN core.agent_work_outbox work ON work.request_id = step.request_id
             WHERE step.run_id = ? AND step.request_id IS NOT NULL
            """,
            Integer.class,
            run.id());
    assertThat(joined).as("workflow step must join to exactly one durable work row").isEqualTo(1);

    assertThat(
            jdbc.queryForObject(
                "SELECT agent_type FROM core.agent_work_outbox WHERE request_id = ?",
                String.class,
                adaptationRequestId))
        .isEqualTo("ADAPTATION");

    // The third leg of the chain. Once the dispatcher runs this work it records an execution under
    // the same request identity, so the whole path must join end to end:
    //   learning_workflow_step -> agent_work_outbox -> ai_execution
    seedDispatchedExecution(jdbc, adaptationRequestId);
    Integer chained =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM core.learning_workflow_step step
              JOIN core.agent_work_outbox work ON work.request_id = step.request_id
              JOIN core.ai_execution execution ON execution.request_id = work.request_id
             WHERE step.run_id = ? AND step.request_id IS NOT NULL
            """,
            Integer.class,
            run.id());
    assertThat(chained)
        .as("step -> outbox -> ai_execution must join end to end on one request identity")
        .isEqualTo(1);
  }

  /** The execution row the M2-T03 dispatcher writes once it delivers the adaptation work. */
  private void seedDispatchedExecution(JdbcTemplate jdbc, String requestId) {
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, agent_version,
           agent_run_id, prompt_template_id, prompt_version, model_route, status,
           request_digest, proposal_digest, started_at, completed_at)
        VALUES (?, ?, 'interaction-correlation', 'ADAPTATION', '1.0', 'ADAPTATION_AGENT_V1', ?,
                'ADAPT', 'ADAPT_V1', 'ci-fake', 'SUCCEEDED', ?, ?, CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        requestId,
        "run-adapt-" + UUID.randomUUID(),
        "d".repeat(64),
        "e".repeat(64));
  }

  @Test
  void theRecomputeResultRefIdentifiesTheExactSnapshotEvenWhenANewerOneExists() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    MasteryRepository mastery = new MasteryRepository(jdbc);
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("wf-lineage").id();
    Run run = startRun(jdbc, runs, "lineage", learnerId);

    MasterySnapshot produced = insertSnapshot(jdbc, learnerId);
    StepClaim claim =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE)
            .orElseThrow();
    runs.finishClaimedStep(claim, StepStatus.COMPLETED, null, null, produced.id());

    // An unrelated learner event lands and becomes the newest snapshot.
    MasterySnapshot newer = insertSnapshot(jdbc, learnerId);
    assertThat(newer.id()).isNotEqualTo(produced.id());
    assertThat(mastery.findLatestSnapshot(learnerId, SKILL, CURRICULUM).orElseThrow().id())
        .isEqualTo(newer.id());

    UUID recorded = runs.step(run.id(), Step.first()).orElseThrow().resultRef();
    assertThat(recorded)
        .as("the workflow must consume the snapshot it produced, not whatever is newest")
        .isEqualTo(produced.id());
    assertThat(mastery.findById(recorded)).isPresent();
  }

  @Test
  void anAbandonedClaimIsReclaimableOnlyAfterItsLeaseExpires() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "abandoned-claim");

    // A worker claims the step and its process dies: the token is never cleared and no completion
    // ever arrives.
    StepClaim abandoned =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                LearningWorkflowPolicy.CLAIM_LEASE)
            .orElseThrow();

    // While the lease holds, nobody may take it. This is the guarantee that stops a slow worker
    // from having its step stolen and a model called twice.
    assertThat(
            runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                LearningWorkflowPolicy.CLAIM_LEASE))
        .as("a live lease is not reclaimable")
        .isEmpty();

    // Once it expires, another instance takes over. Expressed as a zero lease rather than by
    // sleeping: the predicate is a comparison against claimed_at, so this exercises the same SQL.
    StepClaim reclaimed =
        runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                java.time.Duration.ZERO)
            .orElseThrow();

    assertThat(reclaimed.executionToken()).isNotEqualTo(abandoned.executionToken());
    assertThat(reclaimed.attemptCount())
        .as("reclaim spends exactly one further attempt")
        .isEqualTo(2);
    // The dead worker, were it somehow alive, loses to the same compare-and-set that rejects a
    // cancelled one.
    assertThat(runs.finishClaimedStep(abandoned, StepStatus.COMPLETED, null, "stale", null))
        .as("the superseded claim cannot complete the step")
        .isFalse();
    assertThat(runs.finishClaimedStep(reclaimed, StepStatus.COMPLETED, null, null, null)).isTrue();
  }

  @Test
  void reclaimStaysBoundedByTheAttemptCeiling() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "abandoned-bounded");

    // Every attempt is abandoned rather than failed, so recovery is the only thing spending them.
    for (int attempt = 1; attempt <= LearningWorkflowPolicy.MAX_STEP_ATTEMPTS; attempt++) {
      assertThat(
              runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                  java.time.Duration.ZERO))
          .as("attempt %s", attempt)
          .isPresent();
    }

    assertThat(
            runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                java.time.Duration.ZERO))
        .as("an abandoned claim cannot be recovered indefinitely")
        .isEmpty();
    assertThat(runs.step(run.id(), Step.first()).orElseThrow().attemptCount())
        .isEqualTo(LearningWorkflowPolicy.MAX_STEP_ATTEMPTS);
  }

  @Test
  void aCancelledRunIsNotReclaimableEvenAfterTheLeaseExpires() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "abandoned-cancelled");

    runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
            LearningWorkflowPolicy.CLAIM_LEASE)
        .orElseThrow();
    runs.finishRun(run.id(), Status.CANCELLED, "CANCELLED_BY_OPERATOR");
    runs.markCurrentStepTerminal(
        run.id(), Step.first(), StepStatus.CANCELLED, "CANCELLED_BY_OPERATOR");

    assertThat(
            runs.claimStep(run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                java.time.Duration.ZERO))
        .as("recovery must not resurrect a run an operator ended")
        .isEmpty();
  }

  // Java text blocks: JSON quotes need no escaping, which keeps these fixtures readable.
  private static final String JSON_EVIDENCE =
      """
      ["e1"]
      """;
  private static final String JSON_ACCEPTED =
      """
      ["ACCEPTED"]
      """;

  // --- DIAGNOSE recovery states, against the real execution ledger -------------------------------

  @Test
  void crashAfterCommissionBeforeProviderInvocationIsIndeterminateAndClosedNotRedispatched() {
    JdbcTemplate jdbc = runtimeJdbc();
    var executions = executionRepository(jdbc);
    String requestId = "wf-diag-crash-commission";

    // Exactly the durable state a JVM death between the commission commit and the provider call
    // leaves behind: a STARTED event and nothing else.
    commissionOnly(jdbc, requestId);

    assertThat(executions.findExecutionState(requestId).state()).isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.COMMISSIONED);

    // Recovery closes it so the ledger holds no unresolved commission, and the request stays
    // undispatchable: the terminal row is what commissioning refuses against next time.
    assertThat(executions.closeAbandonedExecution(requestId, "AI_EXECUTION_ABANDONED")).isTrue();
    assertThat(executions.findExecutionState(requestId).state()).isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.FAILED);
    assertThat(executions.findExecutionState(requestId).errorCode())
        .isEqualTo("AI_EXECUTION_ABANDONED");

    // Closing twice must neither overwrite nor duplicate.
    assertThat(executions.closeAbandonedExecution(requestId, "AI_EXECUTION_ABANDONED")).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core.ai_execution WHERE request_id = ?",
                Integer.class,
                requestId))
        .isEqualTo(1);
  }

  @Test
  void crashAfterProviderSuccessButBeforeTheGateDecisionIsDetectedAsUnrecoverable() {
    JdbcTemplate jdbc = runtimeJdbc();
    var executions = executionRepository(jdbc);
    String requestId = "wf-diag-crash-success";

    commissionOnly(jdbc, requestId);
    succeededExecution(jdbc, requestId);

    assertThat(executions.findExecutionState(requestId).state()).isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.SUCCEEDED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.proposal_gate_decision WHERE request_id = ?",
                Integer.class,
                requestId))
        .as("this is the state the proposed fix must eliminate: a success with no verdict")
        .isZero();

    // The proposal itself is not retained, only its digest, which is why the state cannot be
    // re-gated and has to be reported rather than silently retried.
    assertThat(
            jdbc.queryForObject(
                "SELECT proposal_digest FROM core.ai_execution WHERE request_id = ?",
                String.class,
                requestId))
        .as("a digest is all there is; it cannot reconstruct a proposal")
        .hasSize(64);

    // An abandonment must never overwrite a real success.
    assertThat(executions.closeAbandonedExecution(requestId, "AI_EXECUTION_ABANDONED")).isFalse();
    assertThat(executions.findExecutionState(requestId).state()).isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.SUCCEEDED);
  }

  @Test
  void crashAfterTheGateDecisionButBeforeWorkflowAdoptionRecoversTheVerdict() {
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-crash-decision";
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("diag-adopt").id();

    commissionOnly(jdbc, requestId);
    succeededExecution(jdbc, requestId);
    gateDecision(jdbc, learnerId, requestId, true);

    var decisions =
        new io.ramals.learningplatform.grounding.JdbcProposalGateDecisionRepository(jdbc);
    var recovered =
        decisions.findDecision(
            requestId, io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC);

    assertThat(recovered).isPresent();
    assertThat(recovered.orElseThrow().accepted())
        .as("a verdict already recorded must survive the worker that obtained it")
        .isTrue();
    assertThat(recovered.orElseThrow().requestId()).isEqualTo(requestId);
  }

  @Test
  void anExhaustedStepIsJudgedByTheDatabaseClockThatTheClaimPredicateUses() {
    JdbcTemplate jdbc = runtimeJdbc();
    LearningWorkflowRepository runs = new LearningWorkflowRepository(jdbc);
    Run run = startRun(jdbc, runs, "db-clock");

    for (int attempt = 1; attempt <= LearningWorkflowPolicy.MAX_STEP_ATTEMPTS; attempt++) {
      runs.claimStep(
              run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
              java.time.Duration.ZERO)
          .orElseThrow();
    }

    // Same predicate, same clock: whatever the claim refuses, this must call exhausted.
    assertThat(
            runs.claimStep(
                run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                java.time.Duration.ZERO))
        .isEmpty();
    assertThat(
            runs.claimableButExhausted(
                run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                java.time.Duration.ZERO))
        .isTrue();
    // Under a live lease the step is owned, not exhausted, however many attempts it has spent.
    assertThat(
            runs.claimableButExhausted(
                run.id(), Step.first(), LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                java.time.Duration.ofHours(1)))
        .isFalse();
  }

  @Test
  void aRealSuccessCommittedDuringTheRaceIsNeitherOverwrittenNorMisreported() {
    JdbcTemplate jdbc = runtimeJdbc();
    var executions = executionRepository(jdbc);
    String requestId = "wf-diag-race-success";

    // The recovery worker observes COMMISSIONED...
    commissionOnly(jdbc, requestId);
    assertThat(executions.findExecutionState(requestId).state()).isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.COMMISSIONED);

    // ...and the original worker commits its real outcome before the close lands.
    succeededExecution(jdbc, requestId);

    assertThat(executions.closeAbandonedExecution(requestId, "AI_EXECUTION_ABANDONED"))
        .as("the conditional write must lose, and say so")
        .isFalse();
    assertThat(executions.findExecutionState(requestId).state())
        .as("the real outcome stands")
        .isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.SUCCEEDED);
    assertThat(executions.findExecutionState(requestId).errorCode()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core.ai_execution WHERE request_id = ?",
                Integer.class,
                requestId))
        .isEqualTo(1);
  }

  @Test
  void aRealFailureCommittedDuringTheRaceKeepsItsOwnErrorCode() {
    JdbcTemplate jdbc = runtimeJdbc();
    var executions = executionRepository(jdbc);
    String requestId = "wf-diag-race-failure";

    commissionOnly(jdbc, requestId);
    assertThat(executions.findExecutionState(requestId).state()).isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.COMMISSIONED);
    failedExecution(jdbc, requestId, "AI_TIMEOUT");

    assertThat(executions.closeAbandonedExecution(requestId, "AI_EXECUTION_ABANDONED")).isFalse();
    assertThat(executions.findExecutionState(requestId).errorCode())
        .as("a real provider failure must not be relabelled as an abandonment")
        .isEqualTo("AI_TIMEOUT");
  }

  @Test
  void twoRecoveryWorkersRacingToCloseTheSameCommissionProduceOneTerminalRow() {
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-race-peers";
    commissionOnly(jdbc, requestId);

    List<Boolean> outcomes =
        List.of(
            executionRepository(runtimeJdbc())
                .closeAbandonedExecution(requestId, "AI_EXECUTION_ABANDONED"),
            executionRepository(runtimeJdbc())
                .closeAbandonedExecution(requestId, "AI_EXECUTION_ABANDONED"));

    assertThat(outcomes.stream().filter(Boolean::booleanValue).count())
        .as("exactly one worker may close a commission")
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core.ai_execution WHERE request_id = ?",
                Integer.class,
                requestId))
        .isEqualTo(1);
  }

  private void failedExecution(JdbcTemplate jdbc, String requestId, String errorCode) {
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, status, error_code,
           request_digest, started_at, completed_at)
        VALUES (?, ?, ?, 'DIAGNOSTIC', '1.0', 'FAILED', ?, ?, CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        requestId,
        "interaction-" + requestId,
        errorCode,
        "f".repeat(64));
  }

  // --- execution success and gate decision commit atomically -------------------------------------

  @Test
  void anAcceptedProposalCommitsTheSuccessRowAndItsDecisionTogether() {
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-atomic-accepted";
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("atomic-accepted").id();

    commissionOnly(jdbc, requestId);
    groundingRecord(jdbc, learnerId, requestId);
    outcomeWriter(jdbc)
        .commitSuccess(
            diagnosticRequest(requestId),
            diagnosticEnvelope(requestId),
            Instant.now(),
            Instant.now(),
            gatedDecision(requestId, true));

    assertThat(executionRepository(jdbc).findExecutionState(requestId).state())
        .isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.SUCCEEDED);
    assertThat(decisionRepository(jdbc).findDecision(requestId, io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC))
        .isPresent();
    assertThat(decisionRepository(jdbc).findDecision(requestId, io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC)
            .orElseThrow().accepted())
        .isTrue();
  }

  @Test
  void aRejectedProposalAlsoCommitsTheSuccessRowAndItsDecisionTogether() {
    // A rejection is a successful execution with an unfavourable verdict. Both rows still belong.
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-atomic-rejected";
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("atomic-rejected").id();

    commissionOnly(jdbc, requestId);
    groundingRecord(jdbc, learnerId, requestId);
    outcomeWriter(jdbc)
        .commitSuccess(
            diagnosticRequest(requestId),
            diagnosticEnvelope(requestId),
            Instant.now(),
            Instant.now(),
            gatedDecision(requestId, false));

    assertThat(executionRepository(jdbc).findExecutionState(requestId).state())
        .isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.SUCCEEDED);
    assertThat(decisionRepository(jdbc).findDecision(requestId, io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC)
            .orElseThrow().accepted())
        .isFalse();
  }

  @Test
  void aMalformedPayloadStillCommitsAnAuditableRejectionWithTheSuccessRow() {
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-atomic-malformed";
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("atomic-malformed").id();

    commissionOnly(jdbc, requestId);
    groundingRecord(jdbc, learnerId, requestId);
    outcomeWriter(jdbc)
        .commitSuccess(
            diagnosticRequest(requestId),
            diagnosticEnvelope(requestId),
            Instant.now(),
            Instant.now(),
            new io.ramals.learningplatform.diagnosticassessment.DiagnosticOutcomeWriter.PendingDecision.PreParse(
                new io.ramals.learningplatform.grounding.ProposalGateDecisionPort.PreParseRejection(
                    "proposal-" + requestId,
                    requestId,
                    "run-" + requestId,
                    "ctx-" + requestId,
                    io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC,
                    io.ramals.learningplatform.grounding.ProposalGateReason.PROPOSAL_INVALID,
                    "DIAGNOSTIC_PAYLOAD_INVALID",
                    new io.ramals.learningplatform.grounding.ProposalGateDecisionPort.DecisionCorrelation(
                        "interaction-" + requestId, "trace-" + requestId))));

    assertThat(executionRepository(jdbc).findExecutionState(requestId).state())
        .isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.SUCCEEDED);
    assertThat(decisionRepository(jdbc).findDecision(requestId, io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC))
        .as("a payload that could not be read is still an outcome worth auditing")
        .isPresent();
  }

  @Test
  void aFailureWhilePersistingTheDecisionLeavesNeitherRow() {
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-atomic-rollback";

    commissionOnly(jdbc, requestId);
    // No grounding_retrieval_record for this context, so the decision insert violates its foreign
    // key. The execution row is written first inside the same transaction, so if the boundary were
    // wrong it would survive the decision's failure.
    assertThatThrownBy(
            () ->
                outcomeWriter(jdbc)
                    .commitSuccess(
                        diagnosticRequest(requestId),
                        diagnosticEnvelope(requestId),
                        Instant.now(),
                        Instant.now(),
                        gatedDecision(requestId, true)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    assertThat(executionRepository(jdbc).findExecutionState(requestId).state())
        .as("the success row must not survive a decision that failed to persist")
        .isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.COMMISSIONED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core.ai_execution WHERE request_id = ?",
                Integer.class,
                requestId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.proposal_gate_decision WHERE request_id = ?",
                Integer.class,
                requestId))
        .isZero();
  }

  @Test
  void noDiagnosticSuccessRowCanExistWithoutItsGateDecision() {
    // The invariant the whole change exists to establish, asserted over everything this suite wrote.
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-atomic-invariant";
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("atomic-invariant").id();

    commissionOnly(jdbc, requestId);
    groundingRecord(jdbc, learnerId, requestId);
    outcomeWriter(jdbc)
        .commitSuccess(
            diagnosticRequest(requestId),
            diagnosticEnvelope(requestId),
            Instant.now(),
            Instant.now(),
            gatedDecision(requestId, true));

    Integer orphans =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM core.ai_execution execution
             WHERE execution.agent_type = 'DIAGNOSTIC'
               AND execution.status = 'SUCCEEDED'
               AND execution.request_id LIKE 'wf-diag-atomic-%'
               AND NOT EXISTS (
                     SELECT 1 FROM ledger.proposal_gate_decision decision
                      WHERE decision.request_id = execution.request_id
                        AND decision.proposal_type = 'DIAGNOSTIC')
            """,
            Integer.class);
    assertThat(orphans)
        .as("state 4, a SUCCEEDED diagnostic execution with no verdict, must be unreachable")
        .isZero();
  }

  @Test
  void aCommittedDecisionIsAdoptedOnRecoveryWithoutRedispatch() {
    JdbcTemplate jdbc = runtimeJdbc();
    String requestId = "wf-diag-atomic-adopt";
    UUID learnerId = new LearnerRepository(jdbc).provisionForSubject("atomic-adopt").id();

    commissionOnly(jdbc, requestId);
    groundingRecord(jdbc, learnerId, requestId);
    outcomeWriter(jdbc)
        .commitSuccess(
            diagnosticRequest(requestId),
            diagnosticEnvelope(requestId),
            Instant.now(),
            Instant.now(),
            gatedDecision(requestId, true));

    // A worker that dies here and comes back finds the verdict, not an undecided success.
    var recovered = decisionRepository(jdbc).findDecision(requestId, io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC);
    assertThat(recovered).isPresent();
    assertThat(recovered.orElseThrow().accepted()).isTrue();
    assertThat(executionRepository(jdbc).findExecutionState(requestId).state())
        .as("the execution stays terminal, so commissioning still refuses a redispatch")
        .isEqualTo(io.ramals.learningplatform.execution.AiExecutionRecoveryPort.ExecutionState.SUCCEEDED);
  }

  private io.ramals.learningplatform.diagnosticassessment.DiagnosticOutcomeWriter outcomeWriter(JdbcTemplate jdbc) {
    return new io.ramals.learningplatform.diagnosticassessment.TransactionalDiagnosticOutcomeWriter(
        executionRepository(jdbc),
        decisionRepository(jdbc),
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getDataSource()));
  }

  private io.ramals.learningplatform.grounding.JdbcProposalGateDecisionRepository decisionRepository(JdbcTemplate jdbc) {
    return new io.ramals.learningplatform.grounding.JdbcProposalGateDecisionRepository(jdbc);
  }

  private io.ramals.learningplatform.diagnosticassessment.DiagnosticOutcomeWriter.PendingDecision gatedDecision(String requestId, boolean accepted) {
    return new io.ramals.learningplatform.diagnosticassessment.DiagnosticOutcomeWriter.PendingDecision.Gated(
        new io.ramals.learningplatform.grounding.ProposalGroundingRequest(
            "1.0",
            "proposal-" + requestId,
            requestId,
            "run-" + requestId,
            "ctx-" + requestId,
            io.ramals.learningplatform.grounding.ProposalType.DIAGNOSTIC,
            new java.math.BigDecimal("0.9000"),
            java.util.List.of()),
        new io.ramals.learningplatform.grounding.ProposalGateResult(
            accepted,
            // ck_proposal_gate_reasons requires a non-empty array: an accepted decision still says
            // why, so the audit never has to infer the outcome from an absence.
            accepted
                ? java.util.List.of(io.ramals.learningplatform.grounding.ProposalGateReason.ACCEPTED)
                : java.util.List.of(io.ramals.learningplatform.grounding.ProposalGateReason.PROPOSAL_INVALID),
            java.util.Set.of()),
        new io.ramals.learningplatform.grounding.ProposalGateDecisionPort.DecisionCorrelation(
            "interaction-" + requestId, "trace-" + requestId));
  }

  private io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest diagnosticRequest(
      String requestId) {
    return new io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest(
        "1.0",
        "interaction-" + requestId,
        requestId,
        new io.ramals.learningplatform.ai.contract.Constraints(
            io.ramals.learningplatform.ai.contract.InteractionClass.INTERACTIVE_AI,
            12_000, null, null, null),
        null);
  }

  private io.ramals.learningplatform.ai.contract.AiProposalEnvelope diagnosticEnvelope(
      String requestId) {
    return new io.ramals.learningplatform.ai.contract.AiProposalEnvelope(
        "1.0",
        "proposal-" + requestId,
        io.ramals.learningplatform.ai.contract.AgentType.DIAGNOSTIC,
        "DIAGNOSTIC_AGENT_V1",
        "run-" + requestId,
        "DIAGNOSE",
        "DIAGNOSE_V1",
        "diagnostic-default",
        io.ramals.learningplatform.ai.contract.TrustLevel.NON_AUTHORITATIVE,
        null,
        List.of(),
        java.util.Map.of(),
        null,
        null);
  }

  private void groundingRecord(JdbcTemplate jdbc, UUID learnerId, String requestId) {
    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at, source_refs,
           source_count)
        VALUES (?, ?, 'PROPOSAL_GROUNDING_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', CAST(? AS jsonb), 1)
        ON CONFLICT (context_id) DO NOTHING
        """,
        "ctx-" + requestId,
        learnerId,
        JSON_EVIDENCE);
  }

  private io.ramals.learningplatform.execution.AiExecutionRepository executionRepository(
      JdbcTemplate jdbc) {
    return new io.ramals.learningplatform.execution.AiExecutionRepository(
        jdbc, tools.jackson.databind.json.JsonMapper.builder().build());
  }

  /** The STARTED event a commission commits before any provider call. */
  private void commissionOnly(JdbcTemplate jdbc, String requestId) {
    jdbc.update(
        """
        INSERT INTO core.ai_execution_event
          (id, request_id, interaction_id, agent_type, contract_version, event_type,
           request_digest, occurred_at)
        VALUES (?, ?, ?, 'DIAGNOSTIC', '1.0', 'STARTED', ?, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        requestId,
        "interaction-" + requestId,
        "f".repeat(64));
  }

  private void succeededExecution(JdbcTemplate jdbc, String requestId) {
    jdbc.update(
        """
        INSERT INTO core.ai_execution
          (id, request_id, interaction_id, agent_type, contract_version, agent_version,
           agent_run_id, prompt_template_id, prompt_version, model_route, status,
           request_digest, proposal_digest, started_at, completed_at)
        VALUES (?, ?, ?, 'DIAGNOSTIC', '1.0', 'DIAGNOSTIC_AGENT_V1', ?, 'DIAGNOSE', 'DIAGNOSE_V1',
                'ci-fake', 'SUCCEEDED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        requestId,
        "interaction-" + requestId,
        "run-" + requestId,
        "f".repeat(64),
        "a".repeat(64));
  }

  private void gateDecision(JdbcTemplate jdbc, UUID learnerId, String requestId, boolean accepted) {
    String contextId = "ctx-" + requestId;
    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at, source_refs,
           source_count)
        VALUES (?, ?, 'PROPOSAL_GROUNDING_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', CAST(? AS jsonb), 1)
        """,
        contextId,
        learnerId,
        JSON_EVIDENCE);
    jdbc.update(
        """
        INSERT INTO ledger.proposal_gate_decision
          (id, proposal_id, request_id, agent_run_id, context_id, proposal_type, accepted,
           reason_codes, referenced_evidence_ids, policy_version, interaction_id, trace_id)
        VALUES (?, ?, ?, ?, ?, 'DIAGNOSTIC', ?, CAST(? AS jsonb), CAST('[]' AS jsonb),
                'PROPOSAL_GROUNDING_V1', ?, ?)
        """,
        UUID.randomUUID(),
        "proposal-" + requestId,
        requestId,
        "run-" + requestId,
        contextId,
        accepted,
        JSON_ACCEPTED,
        "interaction-" + requestId,
        "trace-" + requestId);
  }

  private Run startRun(JdbcTemplate jdbc, LearningWorkflowRepository runs, String key) {
    return startRun(jdbc, runs, key, new LearnerRepository(jdbc).provisionForSubject(key).id());
  }

  private Run startRun(
      JdbcTemplate jdbc, LearningWorkflowRepository runs, String key, UUID learnerId) {
    String requestId = "eval-" + key;
    UUID attemptId = seedAttempt(jdbc, learnerId);
    seedEvaluationDecision(jdbc, learnerId, requestId, attemptId);
    return runs.startOrGet(
        "EVALUATION_TO_ADAPTATION:" + requestId,
        learnerId,
        SKILL,
        CURRICULUM,
        attemptId,
        ASSESSMENT,
        new BigDecimal("0.6000"),
        requestId,
        "interaction-" + key,
        "trace-" + key,
        Instant.now().plusSeconds(600));
  }

  /** The run's FK parents: a grounding record, a successful execution, and a gate decision. */
  private void seedEvaluationDecision(
      JdbcTemplate jdbc, UUID learnerId, String requestId, UUID attemptId) {
    String contextId = "ctx-" + requestId;
    jdbc.update(
        """
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at, source_refs,
           source_count)
        VALUES (?, ?, 'EVALUATION_POLICY_V1', CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes', CAST(? AS jsonb), 1)
        """,
        contextId,
        learnerId,
        "[\"answer-evidence\"]");
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
        "interaction-" + requestId,
        "run-" + requestId,
        "a".repeat(64),
        "b".repeat(64));
    jdbc.update(
        """
        INSERT INTO ledger.assessment_evaluation_decision
          (id, proposal_id, request_id, agent_run_id, ai_execution_id, context_id,
           answer_evidence_id, answer_version, rubric_version, outcome, reason_codes,
           referenced_evidence_ids, dimension_results, feedback, confidence, deterministic_check,
           policy_version, decision_digest, interaction_id, trace_id,
           learner_id, skill_id, curriculum_version_id, attempt_id, assessment_version_id,
           normalized_score, score_policy_version)
        VALUES (?, ?, ?, ?, ?, ?, 'answer-evidence', 'answer-v1', 'rubric-v1', 'ACCEPTED',
                CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), 'Feedback.', 0.85,
                'NOT_APPLICABLE', 'EVALUATION_GATE_V1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        "proposal-" + requestId,
        requestId,
        "run-" + requestId,
        executionId,
        contextId,
        "[\"ACCEPTED\"]",
        "[\"answer-evidence\"]",
        // An ACCEPTED decision must carry rubric results; ck_assessment_evaluation_parsed_result
        // refuses an empty array, which is the M2-T12 constraint doing its job.
        "[{\"dimensionId\":\"accuracy\",\"score\":3,\"maxScore\":4}]",
        "c".repeat(64),
        "interaction-" + requestId,
        "trace-" + requestId,
        learnerId,
        SKILL,
        CURRICULUM,
        attemptId,
        ASSESSMENT,
        new BigDecimal("0.7500"),
        "EVALUATION_SCORE_POLICY_V1");
  }

  private UUID seedAttempt(JdbcTemplate jdbc, UUID learnerId) {
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'COMPLETED', ?)
        """,
        attemptId,
        learnerId,
        ASSESSMENT,
        "wf-" + attemptId);
    return attemptId;
  }

  private MasterySnapshot insertSnapshot(JdbcTemplate jdbc, UUID learnerId) {
    MasteryRepository mastery = new MasteryRepository(jdbc);
    mastery.ensureAggregate(learnerId, SKILL, CURRICULUM);
    int next = mastery.lockAggregateVersion(learnerId, SKILL, CURRICULUM) + 1;
    MasterySnapshot snapshot =
        mastery.insertSnapshot(
            new MasterySnapshotDraft(
                learnerId, SKILL, CURRICULUM, next, new BigDecimal("0.6000"),
                MasteryStatus.NEEDS_PRACTICE, new BigDecimal("0.8000"), new BigDecimal("0.7000"),
                new BigDecimal("0.7000"), 3, 5, "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V1",
                "interaction-lineage"));
    mastery.advanceAggregateVersion(learnerId, SKILL, CURRICULUM, next);
    return snapshot;
  }

  private UUID insertDecisionRecord(JdbcTemplate jdbc, UUID learnerId, MasterySnapshot snapshot) {
    UUID decisionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ledger.decision_record
          (id, learner_id, skill_id, curriculum_version_id, decision_type, recommended_action,
           reason_code, mastery_status, policy_decision, source_snapshot_id, aggregate_version,
           mastery_score, evidence_confidence, mastery_threshold, confidence_threshold,
           evidence_count, items_considered, mastery_algorithm_version,
           confidence_algorithm_version, policy_version, interaction_id, trace_id)
        VALUES (?, ?, ?, ?, 'RECOMMENDATION', 'PRACTICE', 'BELOW_THRESHOLD', 'NEEDS_PRACTICE',
                'PRACTICE', ?, ?, 0.6000, 0.7000, 0.8000, 0.7000, 3, 5, 'WEIGHTED_MASTERY_V1',
                'EVIDENCE_CONFIDENCE_V1', 'RECOMMENDATION_POLICY_V1', ?, ?)
        """,
        decisionId,
        learnerId,
        SKILL,
        CURRICULUM,
        snapshot.id(),
        snapshot.aggregateVersion(),
        "interaction-correlation",
        "trace-correlation");
    return decisionId;
  }

  private io.ramals.learningplatform.recommendation.DecisionRecord decisionRecord(
      JdbcTemplate jdbc, UUID decisionId) {
    return new io.ramals.learningplatform.recommendation.RecommendationRepository(jdbc)
        .findDecisionById(decisionId)
        .orElseThrow();
  }

  private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CyclicBarrier gate = new CyclicBarrier(2);
      Future<T> a = pool.submit(barrier(gate, first));
      Future<T> b = pool.submit(barrier(gate, second));
      return List.of(a.get(), b.get());
    } finally {
      pool.shutdownNow();
    }
  }

  private static <T> Callable<T> barrier(CyclicBarrier gate, Callable<T> work) {
    return () -> {
      gate.await();
      return work.call();
    };
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
