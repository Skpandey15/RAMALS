package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.Usage;
import io.ramals.learningplatform.observability.UuidV7;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** JDBC persistence for append-only AI execution provenance. */
@Repository
public class AiExecutionRepository {

  private static final Logger LOGGER = LoggerFactory.getLogger(AiExecutionRepository.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public AiExecutionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<AiExecution> findByRequestId(String requestId) {
    return jdbc.query(SELECT + " WHERE request_id = ?", MAPPER, requestId).stream().findFirst();
  }

  /**
   * The durable state of one request identity, including the diagnostic provider-dispatch fence.
   * Read-only, and never makes an owned or in-flight request dispatchable again.
   */
  public AiExecutionRecoveryPort.RecordedExecution findExecutionState(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return AiExecutionRecoveryPort.RecordedExecution.absent();
    }
    Optional<AiExecution> terminal = findByRequestId(requestId);
    if (terminal.isPresent()) {
      AiExecution execution = terminal.orElseThrow();
      AiExecutionRecoveryPort.ExecutionState state =
          switch (execution.status()) {
            case "SUCCEEDED" -> AiExecutionRecoveryPort.ExecutionState.SUCCEEDED;
            case "INDETERMINATE" -> AiExecutionRecoveryPort.ExecutionState.INDETERMINATE;
            default -> AiExecutionRecoveryPort.ExecutionState.FAILED;
          };
      return new AiExecutionRecoveryPort.RecordedExecution(state, execution.errorCode());
    }
    Optional<DispatchLedgerState> dispatchState =
        jdbc.query(
                """
                SELECT dispatch.state
                  FROM core.ai_execution_event event
                  LEFT JOIN core.ai_execution_dispatch dispatch
                    ON dispatch.request_id = event.request_id
                 WHERE event.request_id = ? AND event.event_type = 'STARTED'
                """,
                (row, number) -> new DispatchLedgerState(row.getString("state")),
                requestId)
            .stream()
            .findFirst();
    if (dispatchState.isEmpty()) {
      return AiExecutionRecoveryPort.RecordedExecution.absent();
    }
    String recordedState = dispatchState.orElseThrow().state();
    AiExecutionRecoveryPort.ExecutionState state =
        recordedState == null
            ? AiExecutionRecoveryPort.ExecutionState.LEGACY_INDETERMINATE
            : switch (recordedState) {
          case "AVAILABLE" -> AiExecutionRecoveryPort.ExecutionState.COMMISSIONED;
          case "DISPATCH_OWNED" -> AiExecutionRecoveryPort.ExecutionState.DISPATCH_OWNED;
          case "IN_FLIGHT" -> AiExecutionRecoveryPort.ExecutionState.IN_FLIGHT;
          case "LEGACY_INDETERMINATE" ->
              AiExecutionRecoveryPort.ExecutionState.LEGACY_INDETERMINATE;
          default -> throw new IllegalStateException("unknown AI dispatch state");
        };
    return new AiExecutionRecoveryPort.RecordedExecution(state, null);
  }

  /**
   * Writes a terminal failure for an owned or in-flight execution whose worker never returned.
   *
   * <p>Guarded on there being no terminal row, so a late-arriving real outcome is never overwritten
   * by an abandonment. The unique constraint on request_id makes the insert safe under a race.
   */
  public boolean closeAbandonedExecution(String requestId, String errorCode) {
    return closeUnresolvedExecution(requestId, errorCode, "FAILED", false);
  }

  /** Closes an ambiguous diagnostic dispatch without claiming a provider success or failure. */
  public boolean closeIndeterminateExecution(String requestId, String errorCode) {
    return closeUnresolvedExecution(requestId, errorCode, "INDETERMINATE", true);
  }

  private boolean closeUnresolvedExecution(
      String requestId, String errorCode, String status, boolean diagnosticOnly) {
    Integer closed =
        jdbc.update(
            """
            INSERT INTO core.ai_execution
            (id, request_id, interaction_id, agent_type, contract_version, status, error_code,
             request_digest, trace_id, started_at, completed_at)
             SELECT ?, event.request_id, event.interaction_id, event.agent_type,
                    event.contract_version, ?, ?, event.request_digest, ?,
                    event.occurred_at, CURRENT_TIMESTAMP
              FROM core.ai_execution_event event
             WHERE event.request_id = ? AND event.event_type = 'STARTED'
                AND (
                  (? = FALSE AND event.agent_type <> 'DIAGNOSTIC')
                  OR
                  (event.agent_type = 'DIAGNOSTIC' AND EXISTS (
                    SELECT 1
                      FROM core.ai_execution_dispatch dispatch
                     WHERE dispatch.request_id = event.request_id
                       AND dispatch.state IN
                         ('DISPATCH_OWNED', 'IN_FLIGHT', 'LEGACY_INDETERMINATE')
                  ))
                )
             ON CONFLICT (request_id) DO NOTHING
            """,
            UuidV7.generate(),
            status,
            errorCode,
            currentTraceId(),
            requestId,
            diagnosticOnly);
    if (closed != null && closed > 0) {
      // Recovery is also part of the append-only lifecycle stream. Without this event a
      // commissioned request would look terminal in ai_execution but would have no terminal event,
      // making a real pod-death reconstruction internally inconsistent.
      jdbc.update(
          """
          INSERT INTO core.ai_execution_event
            (id, request_id, interaction_id, agent_type, contract_version, event_type,
             error_code, request_digest, proposal_digest, occurred_at, started_at, completed_at)
          SELECT ?, execution.request_id, execution.interaction_id, execution.agent_type,
                  execution.contract_version, ?, execution.error_code,
                 execution.request_digest, execution.proposal_digest, CURRENT_TIMESTAMP,
                 execution.started_at, execution.completed_at
            FROM core.ai_execution execution
            WHERE execution.request_id = ? AND execution.status = ?
          ON CONFLICT (request_id, event_type) DO NOTHING
          """,
          UuidV7.generate(),
          status,
          requestId,
          status);
    }
    return closed != null && closed > 0;
  }

  public AiExecutionCommission commission(AiRequestEnvelope request, String agentType) {
    return commission(
        request.requestId(),
        request.interactionId(),
        request.contractVersion(),
        request,
        agentType);
  }

  public AiExecutionCommission commissionDiagnosticAssessment(
      DiagnosticAssessmentRequest request) {
    String requestDigest = digest(request);
    UUID eventId = UuidV7.generate();
    Long inserted =
        jdbc.queryForObject(
            """
            WITH started AS (
              INSERT INTO core.ai_execution_event
                (id, request_id, interaction_id, agent_type, contract_version, event_type,
                 request_digest, occurred_at)
              VALUES (?, ?, ?, 'DIAGNOSTIC', ?, 'STARTED', ?, CURRENT_TIMESTAMP)
              ON CONFLICT (request_id, event_type) DO NOTHING
              RETURNING id, request_id, occurred_at
            ), dispatch AS (
              INSERT INTO core.ai_execution_dispatch
                (request_id, commission_event_id, state, context_id, context_as_of, owner_token,
                 fence, commissioned_at, ownership_acquired_at, invocation_started_at)
              SELECT request_id, id, 'AVAILABLE', ?, ?, NULL, 0, occurred_at, NULL, NULL
                FROM started
              RETURNING request_id
            )
            SELECT count(*) FROM dispatch
            """,
            Long.class,
            eventId,
            request.requestId(),
            request.interactionId(),
            request.contractVersion(),
            requestDigest,
            request.groundedContext().contextId(),
            timestamp(request.groundedContext().asOf()));
    if (inserted != null && inserted > 0) {
      return AiExecutionCommission.claimed();
    }
    return existingCommission(request.requestId(), requestDigest);
  }

  /** Reads reconstruction metadata only while a diagnostic commission is explicitly ownerless. */
  public Optional<DiagnosticCommissionContext> findRecoverableDiagnosticCommission(
      String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return Optional.empty();
    }
    return jdbc.query(
            """
            SELECT dispatch.context_id, dispatch.context_as_of
              FROM core.ai_execution_dispatch dispatch
             WHERE dispatch.request_id = ?
               AND dispatch.state = 'AVAILABLE'
               AND NOT EXISTS (
                 SELECT 1
                   FROM core.ai_execution execution
                  WHERE execution.request_id = dispatch.request_id
               )
            """,
            (row, number) ->
                new DiagnosticCommissionContext(
                    row.getString("context_id"), instant(row, "context_as_of")),
            requestId)
        .stream()
        .findFirst();
  }

  /**
   * Atomically acquires the one permission to make a diagnostic commission's first provider call.
   *
   * <p>The state predicate, absence of a terminal execution, opaque owner token and returned fence
   * are one PostgreSQL compare-and-set. Concurrent replacement workers can both observe AVAILABLE,
   * but only the row returned by this statement grants dispatch authority.
   */
  public AiExecutionDispatchClaim acquireDiagnosticDispatch(String requestId) {
    UUID ownerToken = UuidV7.generate();
    Optional<AiExecutionDispatchClaim> acquired =
        jdbc.query(
                """
                 UPDATE core.ai_execution_dispatch dispatch
                    SET state = 'DISPATCH_OWNED',
                        owner_token = ?,
                        fence = dispatch.fence + 1,
                        ownership_acquired_at = CURRENT_TIMESTAMP
                   FROM core.ai_execution_event commission
                  WHERE dispatch.request_id = ?
                    AND commission.id = dispatch.commission_event_id
                    AND dispatch.state = 'AVAILABLE'
                   AND NOT EXISTS (
                     SELECT 1
                       FROM core.ai_execution execution
                      WHERE execution.request_id = dispatch.request_id
                   )
                 RETURNING dispatch.owner_token, dispatch.fence, commission.request_digest
                """,
                (row, number) ->
                     AiExecutionDispatchClaim.acquired(
                         row.getObject("owner_token", UUID.class),
                         row.getLong("fence"),
                         row.getString("request_digest")),
                ownerToken,
                requestId)
            .stream()
            .findFirst();
    return acquired.orElseGet(() -> unavailableDispatch(requestId));
  }

  /** Marks invocation started only for the exact owner/fence returned by the acquisition CAS. */
  public boolean markDiagnosticProviderInvocationStarted(
      String requestId, AiExecutionDispatchClaim claim) {
    if (claim == null || !claim.acquired()) {
      return false;
    }
    int updated =
        jdbc.update(
            """
            UPDATE core.ai_execution_dispatch dispatch
               SET state = 'IN_FLIGHT', invocation_started_at = CURRENT_TIMESTAMP
             WHERE dispatch.request_id = ?
               AND dispatch.state = 'DISPATCH_OWNED'
               AND dispatch.owner_token = ?
               AND dispatch.fence = ?
               AND NOT EXISTS (
                 SELECT 1
                   FROM core.ai_execution execution
                  WHERE execution.request_id = dispatch.request_id
               )
            """,
            requestId,
            claim.ownerToken(),
            claim.fence());
    return updated == 1;
  }

  private AiExecutionDispatchClaim unavailableDispatch(String requestId) {
    if (findByRequestId(requestId).isPresent()) {
      return AiExecutionDispatchClaim.unavailable(
          AiExecutionDispatchClaim.DispatchState.TERMINAL);
    }
    return jdbc.query(
            "SELECT state FROM core.ai_execution_dispatch WHERE request_id = ?",
            (row, number) ->
                AiExecutionDispatchClaim.unavailable(
                    AiExecutionDispatchClaim.DispatchState.valueOf(row.getString("state"))),
            requestId)
        .stream()
        .findFirst()
        .orElseGet(
            () ->
                AiExecutionDispatchClaim.unavailable(
                    AiExecutionDispatchClaim.DispatchState.ABSENT));
  }

  private AiExecutionCommission commission(
      String requestId,
      String interactionId,
      String contractVersion,
      Object requestBody,
      String agentType) {
    String requestDigest = digest(requestBody);
    // ON CONFLICT is intentional rather than catching DuplicateKeyException. A duplicate-key
    // error aborts the current PostgreSQL transaction; querying the existing STARTED event from
    // that same transaction would then fail with SQLSTATE 25P02 under a real two-replica race.
    int inserted = jdbc.update("""
        INSERT INTO core.ai_execution_event
          (id, request_id, interaction_id, agent_type, contract_version, event_type,
           request_digest, occurred_at)
        VALUES (?, ?, ?, ?, ?, 'STARTED', ?, CURRENT_TIMESTAMP)
        ON CONFLICT (request_id, event_type) DO NOTHING
        """, UuidV7.generate(), requestId, interactionId, agentType,
        contractVersion, requestDigest);
    if (inserted > 0) {
      return AiExecutionCommission.claimed();
    }
    // The existing-event read is deliberately kept after the conflict has been handled by
    // PostgreSQL, so both the winner and the loser of the unique-key race observe the same
    // durable commission state.
    return existingCommission(requestId, requestDigest);
  }

  private AiExecutionCommission existingCommission(
      String requestId, String requestDigest) {
    ExecutionStart existing = jdbc.query("""
        SELECT request_digest
          FROM core.ai_execution_event
         WHERE request_id = ? AND event_type = 'STARTED'
        """, (r, n) -> new ExecutionStart(r.getString("request_digest")), requestId)
        .stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("AI execution commission was not readable"));
    if (!existing.requestDigest().equals(requestDigest)) {
      throw new AiExecutionConflictException("requestId was reused with a different request digest");
    }
    return findByRequestId(requestId)
        .map(AiExecutionCommission::existing)
        .orElseGet(AiExecutionCommission::inProgress);
  }

  public AiExecution insertSuccess(AiRequestEnvelope request, AiProposalEnvelope proposal,
      Instant startedAt, Instant completedAt) {
    return insertSuccess(metadata(request), request, proposal, startedAt, completedAt);
  }

  public AiExecution insertDiagnosticAssessmentSuccess(
      DiagnosticAssessmentRequest request,
      AiProposalEnvelope proposal,
      Instant startedAt,
      Instant completedAt) {
    return insertSuccess(metadata(request), request, proposal, startedAt, completedAt);
  }

  public AiExecution insertFailure(AiRequestEnvelope request, String agentType, String errorCode,
      Instant startedAt, Instant completedAt) {
    return insertFailure(
        metadata(request), request, agentType, "FAILED", errorCode, startedAt, completedAt);
  }

  public AiExecution insertDiagnosticAssessmentFailure(
      DiagnosticAssessmentRequest request,
      String errorCode,
      Instant startedAt,
      Instant completedAt) {
    return insertFailure(
        metadata(request),
        request,
        "DIAGNOSTIC",
        "FAILED",
        errorCode,
        startedAt,
        completedAt);
  }

  public AiExecution insertDiagnosticAssessmentIndeterminate(
      DiagnosticAssessmentRequest request,
      String errorCode,
      Instant startedAt,
      Instant completedAt) {
    return insertFailure(
        metadata(request),
        request,
        "DIAGNOSTIC",
        "INDETERMINATE",
        errorCode,
        startedAt,
        completedAt);
  }

  private AiExecution insertSuccess(
      RequestMetadata request,
      Object requestBody,
      AiProposalEnvelope proposal,
      Instant startedAt,
      Instant completedAt) {
    Usage usage = proposal.usage();
    String requestDigest = digest(requestBody);
    String proposalDigest = digest(proposal);
    AiExecution execution =
        insert(
            request,
            proposal.agentType().name(),
            proposal.agentVersion(),
            proposal.agentRunId(),
            proposal.promptTemplateId(),
            proposal.promptVersion(),
            proposal.modelRoute(),
            proposal.resolvedProvider(),
            proposal.modelId(),
            proposal.routeVersion(),
            traceId(request.interactionId()),
            proposal.providerRequestId(),
            proposal.providerMessageId(),
            proposal.responseDigest(),
            "SUCCEEDED",
            null,
            requestDigest,
            proposalDigest,
            usage,
            startedAt,
            completedAt);
    appendCompletion(execution);
    return execution;
  }

  private AiExecution insertFailure(
      RequestMetadata request,
      Object requestBody,
      String agentType,
      String status,
      String errorCode,
      Instant startedAt,
      Instant completedAt) {
    String requestDigest = digest(requestBody);
    // No proposal came back, so there is no run and no prompt to name. Left null rather
    // than filled with something plausible: a failure that claims a prompt produced it is
    // worse than one that admits it got nothing.
    AiExecution execution = insert(request, agentType,
        null, null, null, null, null, null, null, null, traceId(request.interactionId()),
        null, null, null, status, errorCode, requestDigest, null, null, startedAt, completedAt);
    appendCompletion(execution);
    return execution;
  }

  private void appendCompletion(AiExecution execution) {
    try {
      jdbc.update("""
          INSERT INTO core.ai_execution_event
            (id, request_id, interaction_id, agent_type, contract_version, event_type,
             error_code, request_digest, proposal_digest, provider_request_id,
             provider_message_id, response_digest, occurred_at, started_at, completed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
          """, UuidV7.generate(), execution.requestId(), execution.interactionId(),
          execution.agentType(), execution.contractVersion(), execution.status(), execution.errorCode(),
          execution.requestDigest(), execution.proposalDigest(), execution.providerRequestId(),
          execution.providerMessageId(), execution.responseDigest(), timestamp(execution.startedAt()),
          timestamp(execution.completedAt()));
    } catch (DuplicateKeyException duplicate) {
      TerminalEvent existing = jdbc.query("""
          SELECT event_type, request_digest, proposal_digest, error_code
            FROM core.ai_execution_event
           WHERE request_id = ? AND event_type IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')
          """, (r, n) -> new TerminalEvent(r.getString("event_type"), r.getString("request_digest"),
              r.getString("proposal_digest"), r.getString("error_code")), execution.requestId())
          .stream().findFirst()
          .orElseThrow(() -> new IllegalStateException("AI terminal event conflict was not readable"));
      validateTerminalCompatibility(execution, existing);
    }
  }

  private AiExecution insert(RequestMetadata request, String agentType, String agentVersion,
      String agentRunId, String promptTemplateId, String promptVersion, String modelRoute,
      String resolvedProvider, String modelId, String routeVersion, String traceId,
      String providerRequestId, String providerMessageId, String responseDigest,
      String status, String errorCode,
      String requestDigest, String proposalDigest, Usage usage, Instant startedAt, Instant completedAt) {
    UUID id = UuidV7.generate();
    try {
      jdbc.update("""
          INSERT INTO core.ai_execution
            (id, request_id, interaction_id, agent_type, contract_version, agent_version,
             agent_run_id, prompt_template_id, prompt_version, model_route, model_id, status,
             resolved_provider, route_version, trace_id,
             provider_request_id, provider_message_id, response_digest,
             error_code, request_digest,
             proposal_digest, input_tokens, cached_input_tokens, output_tokens, estimated_cost_usd,
             latency_ms, started_at, completed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, id, request.requestId(), request.interactionId(), agentType, request.contractVersion(),
          agentVersion, agentRunId, promptTemplateId, promptVersion, modelRoute, modelId, status,
          resolvedProvider, routeVersion, traceId, providerRequestId, providerMessageId,
          responseDigest, errorCode, requestDigest,
          proposalDigest, usage == null ? null : usage.inputTokens(),
          usage == null ? null : usage.cachedInputTokens(), usage == null ? null : usage.outputTokens(),
          cost(usage), usage == null ? null : usage.latencyMs(),
          timestamp(startedAt), timestamp(completedAt));
    } catch (DuplicateKeyException duplicate) {
      AiExecution existing = findByRequestId(request.requestId())
          .orElseThrow(() -> new IllegalStateException("AI execution conflict was not readable"));
      validateTerminalCompatibility(existing, status, errorCode, requestDigest, proposalDigest);
      return existing;
    }
    return findByRequestId(request.requestId())
        .orElseThrow(() -> new IllegalStateException("AI execution insert did not persist"));
  }

  private BigDecimal cost(Usage usage) {
    if (usage == null || usage.estimatedCostUsd() == null || usage.estimatedCostUsd().isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(usage.estimatedCostUsd());
    } catch (NumberFormatException invalid) {
      // Still null, because there is no honest number to store — but no longer silent. A cost the
      // platform could not parse and a call that genuinely cost nothing are the same row otherwise,
      // and the difference is the one that matters when a spend report looks too good.
      LOGGER.atWarn()
          .addKeyValue("operation", "ai.execution.cost_unparseable")
          .addKeyValue("reportedCost", usage.estimatedCostUsd())
          .log("provider reported a cost that could not be parsed; recorded as unknown");
      return null;
    }
  }

  private String digest(Object value) {
    try {
      byte[] bytes = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException("could not digest AI execution metadata", failure);
    }
  }

  private static final String SELECT = """
      SELECT id, request_id, interaction_id, agent_type, contract_version, agent_version,
             agent_run_id, prompt_template_id,
             prompt_version, model_route, model_id, status, error_code, request_digest,
             resolved_provider, route_version, trace_id,
             provider_request_id, provider_message_id, response_digest,
             proposal_digest, input_tokens, cached_input_tokens, output_tokens, estimated_cost_usd,
             latency_ms, started_at, completed_at
        FROM core.ai_execution
      """;

  private static final RowMapper<AiExecution> MAPPER = (r, n) -> new AiExecution(
      r.getObject("id", UUID.class), r.getString("request_id"), r.getString("interaction_id"),
      r.getString("agent_type"), r.getString("contract_version"), r.getString("agent_version"),
      r.getString("agent_run_id"), r.getString("prompt_template_id"),
      r.getString("prompt_version"), r.getString("model_route"),
      r.getString("resolved_provider"), r.getString("model_id"),
      r.getString("route_version"), r.getString("trace_id"),
      r.getString("provider_request_id"), r.getString("provider_message_id"),
      r.getString("response_digest"),
      r.getString("status"), r.getString("error_code"), r.getString("request_digest"),
      r.getString("proposal_digest"), (Integer) r.getObject("input_tokens"),
      (Integer) r.getObject("cached_input_tokens"), (Integer) r.getObject("output_tokens"),
      r.getObject("estimated_cost_usd", BigDecimal.class), (Integer) r.getObject("latency_ms"),
      instant(r, "started_at"), instant(r, "completed_at"));

  private static RequestMetadata metadata(AiRequestEnvelope request) {
    return new RequestMetadata(
        request.requestId(), request.interactionId(), request.contractVersion());
  }

  private static RequestMetadata metadata(DiagnosticAssessmentRequest request) {
    return new RequestMetadata(
        request.requestId(), request.interactionId(), request.contractVersion());
  }

  private static String traceId(String interactionId) {
    String traceId = MDC.get("traceId");
    return traceId == null || traceId.isBlank() ? interactionId : traceId;
  }

  /** Returns the actual distributed trace when recovery runs inside a correlated workflow pass. */
  private static String currentTraceId() {
    String traceId = MDC.get("traceId");
    return traceId == null || traceId.isBlank() ? null : traceId;
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private record ExecutionStart(String requestDigest) {}

  private record DispatchLedgerState(String state) {}

  private record RequestMetadata(
      String requestId, String interactionId, String contractVersion) {}

  private record TerminalEvent(String status, String requestDigest, String proposalDigest,
      String errorCode) {}

  static void validateTerminalCompatibility(AiExecution existing, String attemptedStatus,
      String attemptedErrorCode, String attemptedRequestDigest, String attemptedProposalDigest) {
    if (!existing.requestDigest().equals(attemptedRequestDigest)) {
      throw new AiExecutionConflictException("requestId was reused with a different request digest");
    }
    if (!existing.status().equals(attemptedStatus)) {
      throw new AiExecutionConflictException("requestId already has a different terminal outcome");
    }
    if ("SUCCEEDED".equals(attemptedStatus)
        && existing.proposalDigest() != null
        && !existing.proposalDigest().equals(attemptedProposalDigest)) {
      throw new AiExecutionConflictException("requestId was reused with a different proposal digest");
    }
    if (("FAILED".equals(attemptedStatus) || "INDETERMINATE".equals(attemptedStatus))
        && existing.errorCode() != null
        && !existing.errorCode().equals(attemptedErrorCode)) {
      String kind = "FAILED".equals(attemptedStatus) ? "failure" : "indeterminate";
      throw new AiExecutionConflictException(
          "requestId was reused with a different " + kind + " code");
    }
  }

  private static void validateTerminalCompatibility(AiExecution existing, TerminalEvent event) {
    validateTerminalCompatibility(existing, event.status(), event.errorCode(), event.requestDigest(),
        event.proposalDigest());
  }

  public static class AiExecutionConflictException extends RuntimeException {
    public AiExecutionConflictException(String message) { super(message); }
  }

  /**
   * PostgreSQL's JDBC driver cannot bind a {@link Instant} directly.
   *
   * <p>Passing one produces {@code Can't infer the SQL type to use for an instance of
   * java.time.Instant}, so every write to this table failed against a real database while passing
   * everywhere it was tested. M1-T18 found it on a deployed candidate: the adaptation comparison
   * dispatched, the agent answered, and the row could not be written -- and the failure of the
   * failure-recording path meant nothing was left behind to notice.
   *
   * <p>The reason it survived is worth keeping next to the fix: the only production writer was a
   * path no controller reached, and the tests that covered this class did not run against
   * PostgreSQL. A type the driver rejects is invisible to any database that accepts it.
   */
  private static java.sql.Timestamp timestamp(Instant moment) {
    return moment == null ? null : java.sql.Timestamp.from(moment);
  }
}
