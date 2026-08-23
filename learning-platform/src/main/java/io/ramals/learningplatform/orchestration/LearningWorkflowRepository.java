package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.observability.UuidV7;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Status;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepClaim;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepRun;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Durable state for controlled compositions. Every transition is a row an operator can read. */
@Repository
public class LearningWorkflowRepository {

  private static final String RUN_COLUMNS =
      """
      id, workflow_type, policy_version, trigger_key, learner_id, skill_id,
      curriculum_version_id, attempt_id, assessment_version_id, normalized_score,
      evaluation_request_id, status, current_step, terminal_reason, interaction_id, trace_id,
      deadline_at, started_at, completed_at
      """;

  private static final String STEP_COLUMNS =
      """
      id, run_id, step_name, status, attempt_count, reason_code, request_id, result_ref,
      execution_token, claimed_at, started_at, completed_at
      """;

  private final JdbcTemplate jdbc;

  public LearningWorkflowRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a run unless its trigger already produced one, then returns whichever run now owns the
   * trigger. The duplicate trigger collapses here (G05) rather than in the caller, so every entry
   * point gets the same idempotency whether or not it remembered to ask for it.
   */
  public Run startOrGet(
      String triggerKey,
      UUID learnerId,
      UUID skillId,
      UUID curriculumVersionId,
      UUID attemptId,
      UUID assessmentVersionId,
      BigDecimal normalizedScore,
      String evaluationRequestId,
      String interactionId,
      String traceId,
      Instant deadlineAt) {
    jdbc.update(
        """
        INSERT INTO core.learning_workflow_run
          (id, workflow_type, policy_version, trigger_key, learner_id, skill_id,
           curriculum_version_id, attempt_id, assessment_version_id, normalized_score,
           evaluation_request_id, status, current_step, interaction_id, trace_id, deadline_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """,
        UuidV7.generate(),
        LearningWorkflow.TYPE_EVALUATION_TO_ADAPTATION,
        LearningWorkflowPolicy.POLICY_VERSION,
        triggerKey,
        learnerId,
        skillId,
        curriculumVersionId,
        attemptId,
        assessmentVersionId,
        normalizedScore,
        evaluationRequestId,
        Step.first().name(),
        interactionId,
        traceId,
        OffsetDateTime.ofInstant(deadlineAt, ZoneOffset.UTC));
    return findByTriggerKey(triggerKey)
        .orElseThrow(() -> new IllegalStateException("workflow trigger did not resolve to a run"));
  }

  public Optional<Run> findByTriggerKey(String triggerKey) {
    return jdbc
        .query(
            "SELECT " + RUN_COLUMNS + " FROM core.learning_workflow_run WHERE trigger_key = ?",
            runMapper(),
            triggerKey)
        .stream()
        .findFirst();
  }

  public Optional<Run> findById(UUID runId) {
    return jdbc
        .query(
            "SELECT " + RUN_COLUMNS + " FROM core.learning_workflow_run WHERE id = ?",
            runMapper(),
            runId)
        .stream()
        .findFirst();
  }

  /** Runs still in flight, oldest first, so a backlog drains in the order it arrived. */
  public List<Run> running(int limit) {
    return jdbc.query(
        "SELECT " + RUN_COLUMNS
            + " FROM core.learning_workflow_run WHERE status = 'RUNNING'"
            + " ORDER BY started_at, id LIMIT ?",
        runMapper(),
        limit);
  }

  public List<StepRun> steps(UUID runId) {
    return jdbc.query(
        "SELECT " + STEP_COLUMNS
            + " FROM core.learning_workflow_step WHERE run_id = ? ORDER BY step_index",
        stepMapper(),
        runId);
  }

  public Optional<StepRun> step(UUID runId, Step step) {
    return steps(runId).stream().filter(candidate -> candidate.step() == step).findFirst();
  }

  /**
   * Atomically claims the next attempt of a step, or returns empty.
   *
   * <p>One statement, so two workers polling the same run cannot both win. The claim is refused
   * unless the run is still RUNNING and still sitting on this exact step, and unless the step is
   * either unseen, PENDING, or RUNNING under a claim whose lease has expired. The attempt ceiling is
   * part of the same predicate, so a competing caller cannot spend an attempt that the winner is
   * already using.
   *
   * <p>The lease is what makes a dead worker recoverable: a claim is otherwise held forever by a
   * process that no longer exists, and the run reaches its deadline having done nothing. Reclaiming
   * issues a new execution token, so the original worker -- if it is somehow still alive -- fails
   * its completion against the same compare-and-set that rejects a cancelled one.
   *
   * <p>Commits before the caller does any remote work, and holds no transaction across it.
   */
  public Optional<StepClaim> claimStep(
      UUID runId, Step step, int maxAttempts, Duration lease) {
    UUID token = UUID.randomUUID();
    int claimed =
        jdbc.update(
            """
            INSERT INTO core.learning_workflow_step
              (id, run_id, step_name, step_index, status, attempt_count, execution_token,
               claimed_at)
            SELECT ?, run.id, ?, ?, 'RUNNING', 1, ?, CURRENT_TIMESTAMP
              FROM core.learning_workflow_run run
             WHERE run.id = ?
               AND run.status = 'RUNNING'
               AND run.current_step = ?
            ON CONFLICT (run_id, step_name) DO UPDATE
              SET status = 'RUNNING',
                  attempt_count = core.learning_workflow_step.attempt_count + 1,
                  execution_token = EXCLUDED.execution_token,
                  claimed_at = CURRENT_TIMESTAMP,
                  completed_at = NULL,
                  reason_code = NULL,
                  updated_at = CURRENT_TIMESTAMP
             WHERE (core.learning_workflow_step.status = 'PENDING'
                    OR (core.learning_workflow_step.status = 'RUNNING'
                        AND core.learning_workflow_step.claimed_at
                            < CURRENT_TIMESTAMP - CAST(? AS interval)))
               AND core.learning_workflow_step.attempt_count < ?
            """,
            UuidV7.generate(),
            step.name(),
            step.index(),
            token,
            runId,
            step.name(),
            lease.toSeconds() + " seconds",
            maxAttempts);
    if (claimed == 0) {
      return Optional.empty();
    }
    return step(runId, step)
        .filter(current -> token.equals(current.executionToken()))
        .map(current -> new StepClaim(runId, step, current.attemptCount(), token));
  }

  /**
   * Completes a claimed step, but only for the worker that still holds the claim.
   *
   * <p>Returns false when the token no longer matches, which is exactly the stale-worker case: the
   * run was cancelled or timed out while the remote call was in flight, that terminal transition
   * cleared the token, and this completion must not resurrect the step.
   */
  public boolean finishClaimedStep(
      StepClaim claim, StepStatus status, String reasonCode, String requestId, UUID resultRef) {
    return jdbc.update(
            """
            UPDATE core.learning_workflow_step
               SET status = ?, reason_code = ?, request_id = ?, result_ref = ?,
                   execution_token = NULL, completed_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP
             WHERE run_id = ? AND step_name = ? AND execution_token = ?
            """,
            status.name(),
            reasonCode,
            requestId,
            resultRef,
            claim.runId(),
            claim.step().name(),
            claim.executionToken())
        > 0;
  }

  /**
   * Releases a claimed step for another attempt, keeping the attempt count that bounds it.
   *
   * <p>Token-guarded for the same reason as completion. The count must survive the failure that
   * provoked it, or a bounded retry becomes an unbounded one.
   */
  public boolean retryClaimedStep(StepClaim claim, String reasonCode) {
    return jdbc.update(
            """
            UPDATE core.learning_workflow_step
               SET status = 'PENDING', reason_code = ?, execution_token = NULL,
                   completed_at = NULL, updated_at = CURRENT_TIMESTAMP
             WHERE run_id = ? AND step_name = ? AND execution_token = ?
            """,
            reasonCode,
            claim.runId(),
            claim.step().name(),
            claim.executionToken())
        > 0;
  }

  /**
   * Records a step that policy decided never to run.
   *
   * <p>Attempt count stays zero, because no attempt was made. Inserting rather than claiming is the
   * point: reusing the claim helper to create the row would credit a skipped step with an attempt
   * and make the audit describe work that never happened.
   *
   * <p>Does nothing when the step already exists, so a skip can never overwrite a real outcome.
   */
  public void markSkipped(UUID runId, Step step, String reasonCode) {
    jdbc.update(
        """
        INSERT INTO core.learning_workflow_step
          (id, run_id, step_name, step_index, status, attempt_count, reason_code, completed_at)
        VALUES (?, ?, ?, ?, 'SKIPPED', 0, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (run_id, step_name) DO NOTHING
        """,
        UuidV7.generate(),
        runId,
        step.name(),
        step.index(),
        reasonCode);
  }

  /**
   * Marks the step a cancelled or timed-out run was sitting on, without manufacturing an attempt.
   *
   * <p>If the step was claimed, its attempt count is left exactly as it was and the token is
   * cleared, which is what refuses the in-flight worker's later completion. If it was never
   * claimed, the row is created with a count of zero.
   */
  public void markCurrentStepTerminal(UUID runId, Step step, StepStatus status, String reasonCode) {
    jdbc.update(
        """
        INSERT INTO core.learning_workflow_step
          (id, run_id, step_name, step_index, status, attempt_count, reason_code, completed_at)
        VALUES (?, ?, ?, ?, ?, 0, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (run_id, step_name) DO UPDATE
          SET status = EXCLUDED.status,
              reason_code = EXCLUDED.reason_code,
              execution_token = NULL,
              completed_at = CURRENT_TIMESTAMP,
              updated_at = CURRENT_TIMESTAMP
        """,
        UuidV7.generate(),
        runId,
        step.name(),
        step.index(),
        status.name(),
        reasonCode);
  }

  /** Moves the run's cursor forward. Only ever called with a strictly later step. */
  public void advanceTo(UUID runId, Step next) {
    jdbc.update(
        """
        UPDATE core.learning_workflow_run
           SET current_step = ?, updated_at = CURRENT_TIMESTAMP
         WHERE id = ? AND status = 'RUNNING'
        """,
        next.name(),
        runId);
  }

  /**
   * Closes a run. Guarded on RUNNING so a cancellation racing a completion cannot rewrite an
   * already-terminal outcome; the first terminal state a run reaches is the one it keeps.
   */
  public boolean finishRun(UUID runId, Status status, String terminalReason) {
    return jdbc.update(
            """
            UPDATE core.learning_workflow_run
               SET status = ?, terminal_reason = ?, completed_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING'
            """,
            status.name(),
            terminalReason,
            runId)
        > 0;
  }

  private static RowMapper<Run> runMapper() {
    return (result, row) ->
        new Run(
            result.getObject("id", UUID.class),
            result.getString("workflow_type"),
            result.getString("policy_version"),
            result.getString("trigger_key"),
            result.getObject("learner_id", UUID.class),
            result.getObject("skill_id", UUID.class),
            result.getObject("curriculum_version_id", UUID.class),
            result.getObject("attempt_id", UUID.class),
            result.getObject("assessment_version_id", UUID.class),
            result.getBigDecimal("normalized_score"),
            result.getString("evaluation_request_id"),
            Status.valueOf(result.getString("status")),
            stepOrNull(result.getString("current_step")),
            result.getString("terminal_reason"),
            result.getString("interaction_id"),
            result.getString("trace_id"),
            instant(result, "deadline_at"),
            instant(result, "started_at"),
            instant(result, "completed_at"));
  }

  private static RowMapper<StepRun> stepMapper() {
    return (result, row) ->
        new StepRun(
            result.getObject("id", UUID.class),
            result.getObject("run_id", UUID.class),
            Step.valueOf(result.getString("step_name")),
            StepStatus.valueOf(result.getString("status")),
            result.getInt("attempt_count"),
            result.getString("reason_code"),
            result.getString("request_id"),
            result.getObject("result_ref", UUID.class),
            result.getObject("execution_token", UUID.class),
            instant(result, "claimed_at"),
            instant(result, "started_at"),
            instant(result, "completed_at"));
  }

  private static Step stepOrNull(String value) {
    return value == null ? null : Step.valueOf(value);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
