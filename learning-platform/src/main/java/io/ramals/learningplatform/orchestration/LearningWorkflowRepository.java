package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.observability.UuidV7;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Status;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepRun;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
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
      java.math.BigDecimal normalizedScore,
      String evaluationRequestId,
      String interactionId,
      String traceId,
      Instant deadlineAt) {
    jdbc.update(
        """
        INSERT INTO ledger.learning_workflow_run
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
        .orElseThrow(
            () -> new IllegalStateException("workflow trigger did not resolve to a run"));
  }

  public Optional<Run> findByTriggerKey(String triggerKey) {
    return jdbc
        .query(
            "SELECT " + RUN_COLUMNS + " FROM ledger.learning_workflow_run WHERE trigger_key = ?",
            runMapper(),
            triggerKey)
        .stream()
        .findFirst();
  }

  public Optional<Run> findById(UUID runId) {
    return jdbc
        .query(
            "SELECT " + RUN_COLUMNS + " FROM ledger.learning_workflow_run WHERE id = ?",
            runMapper(),
            runId)
        .stream()
        .findFirst();
  }

  /** Runs still in flight, oldest first, so a backlog drains in the order it arrived. */
  public List<Run> running(int limit) {
    return jdbc.query(
        "SELECT " + RUN_COLUMNS
            + " FROM ledger.learning_workflow_run WHERE status = 'RUNNING'"
            + " ORDER BY started_at, id LIMIT ?",
        runMapper(),
        limit);
  }

  public List<StepRun> steps(UUID runId) {
    return jdbc.query(
        """
        SELECT id, run_id, step_name, status, attempt_count, reason_code, request_id,
               result_ref, started_at, completed_at
          FROM ledger.learning_workflow_step
         WHERE run_id = ?
         ORDER BY step_index
        """,
        stepMapper(),
        runId);
  }

  public Optional<StepRun> step(UUID runId, Step step) {
    return steps(runId).stream().filter(candidate -> candidate.step() == step).findFirst();
  }

  /**
   * Marks a step as being attempted, creating it on first sight and incrementing its attempt count
   * on every later one. The unique key on (run_id, step_name) means a repeated attempt updates the
   * one row instead of appending, which is what bounds the composition structurally.
   */
  public void beginStep(UUID runId, Step step) {
    jdbc.update(
        """
        INSERT INTO ledger.learning_workflow_step
          (id, run_id, step_name, step_index, status, attempt_count)
        VALUES (?, ?, ?, ?, 'RUNNING', 1)
        ON CONFLICT (run_id, step_name) DO UPDATE
          SET status = 'RUNNING',
              attempt_count = ledger.learning_workflow_step.attempt_count + 1,
              completed_at = NULL,
              updated_at = CURRENT_TIMESTAMP
        """,
        UuidV7.generate(),
        runId,
        step.name(),
        step.index());
  }

  public void finishStep(
      UUID runId,
      Step step,
      StepStatus status,
      String reasonCode,
      String requestId,
      UUID resultRef) {
    jdbc.update(
        """
        UPDATE ledger.learning_workflow_step
           SET status = ?, reason_code = ?, request_id = ?, result_ref = ?,
               completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
         WHERE run_id = ? AND step_name = ?
        """,
        status.name(),
        reasonCode,
        requestId,
        resultRef,
        runId,
        step.name());
  }

  /**
   * Returns a step to PENDING after a retryable failure, keeping the incremented attempt count.
   *
   * <p>The attempt count is what bounds the retry, so it must survive the failure that provoked it.
   * Rolling the increment back with the step's work is how a bounded retry becomes an unbounded one.
   */
  public void retryStep(UUID runId, Step step, String reasonCode) {
    jdbc.update(
        """
        UPDATE ledger.learning_workflow_step
           SET status = 'PENDING', reason_code = ?, completed_at = NULL,
               updated_at = CURRENT_TIMESTAMP
         WHERE run_id = ? AND step_name = ?
        """,
        reasonCode,
        runId,
        step.name());
  }

  /** Moves the run's cursor forward. Only ever called with a strictly later step. */
  public void advanceTo(UUID runId, Step next) {
    jdbc.update(
        """
        UPDATE ledger.learning_workflow_run
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
            UPDATE ledger.learning_workflow_run
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
