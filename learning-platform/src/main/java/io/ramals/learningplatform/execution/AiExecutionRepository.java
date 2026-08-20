package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** JDBC persistence for append-only AI execution provenance. */
@Repository
public class AiExecutionRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public AiExecutionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<AiExecution> findByRequestId(String requestId) {
    return jdbc.query(SELECT + " WHERE request_id = ?", MAPPER, requestId).stream().findFirst();
  }

  public AiExecutionCommission commission(AiRequestEnvelope request, String agentType) {
    String requestDigest = digest(request);
    try {
      jdbc.update("""
          INSERT INTO core.ai_execution_event
            (id, request_id, interaction_id, agent_type, contract_version, event_type,
             request_digest, occurred_at)
          VALUES (?, ?, ?, ?, ?, 'STARTED', ?, CURRENT_TIMESTAMP)
          """, UuidV7.generate(), request.requestId(), request.interactionId(), agentType,
          request.contractVersion(), requestDigest);
      return AiExecutionCommission.claimed();
    } catch (DuplicateKeyException duplicate) {
      ExecutionStart existing = jdbc.query("""
          SELECT request_digest
            FROM core.ai_execution_event
           WHERE request_id = ? AND event_type = 'STARTED'
          """, (r, n) -> new ExecutionStart(r.getString("request_digest")), request.requestId())
          .stream().findFirst()
          .orElseThrow(() -> new IllegalStateException("AI execution commission was not readable"));
      if (!existing.requestDigest().equals(requestDigest)) {
        throw new AiExecutionConflictException("requestId was reused with a different request digest");
      }
      return findByRequestId(request.requestId())
          .map(AiExecutionCommission::existing)
          .orElseGet(AiExecutionCommission::inProgress);
    }
  }

  public AiExecution insertSuccess(AiRequestEnvelope request, AiProposalEnvelope proposal,
      Instant startedAt, Instant completedAt) {
    Usage usage = proposal.usage();
    String requestDigest = digest(request);
    String proposalDigest = digest(proposal);
    AiExecution execution = insert(request, proposal.agentType().name(), proposal.agentVersion(), proposal.promptVersion(),
        proposal.modelRoute(), null, "SUCCEEDED", null, requestDigest, proposalDigest, usage,
        startedAt, completedAt);
    appendCompletion(request, execution);
    return execution;
  }

  public AiExecution insertFailure(AiRequestEnvelope request, String agentType, String errorCode,
      Instant startedAt, Instant completedAt) {
    String requestDigest = digest(request);
    AiExecution execution = insert(request, agentType, null, null, null, null, "FAILED", errorCode, requestDigest,
        null, null, startedAt, completedAt);
    appendCompletion(request, execution);
    return execution;
  }

  private void appendCompletion(AiRequestEnvelope request, AiExecution execution) {
    try {
      jdbc.update("""
          INSERT INTO core.ai_execution_event
            (id, request_id, interaction_id, agent_type, contract_version, event_type,
             error_code, request_digest, proposal_digest, occurred_at, started_at, completed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
          """, UuidV7.generate(), execution.requestId(), execution.interactionId(),
          execution.agentType(), execution.contractVersion(), execution.status(), execution.errorCode(),
          execution.requestDigest(), execution.proposalDigest(), execution.startedAt(),
          execution.completedAt());
    } catch (DuplicateKeyException duplicate) {
      TerminalEvent existing = jdbc.query("""
          SELECT event_type, request_digest, proposal_digest, error_code
            FROM core.ai_execution_event
           WHERE request_id = ? AND event_type IN ('SUCCEEDED', 'FAILED')
          """, (r, n) -> new TerminalEvent(r.getString("event_type"), r.getString("request_digest"),
              r.getString("proposal_digest"), r.getString("error_code")), execution.requestId())
          .stream().findFirst()
          .orElseThrow(() -> new IllegalStateException("AI terminal event conflict was not readable"));
      validateTerminalCompatibility(execution, existing);
    }
  }

  private AiExecution insert(AiRequestEnvelope request, String agentType, String agentVersion,
      String promptVersion, String modelRoute, String modelId, String status, String errorCode,
      String requestDigest, String proposalDigest, Usage usage, Instant startedAt, Instant completedAt) {
    UUID id = UuidV7.generate();
    try {
      jdbc.update("""
          INSERT INTO core.ai_execution
            (id, request_id, interaction_id, agent_type, contract_version, agent_version,
             prompt_version, model_route, model_id, status, error_code, request_digest,
             proposal_digest, input_tokens, cached_input_tokens, output_tokens, estimated_cost_usd,
             latency_ms, started_at, completed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, id, request.requestId(), request.interactionId(), agentType, request.contractVersion(),
          agentVersion, promptVersion, modelRoute, modelId, status, errorCode, requestDigest,
          proposalDigest, usage == null ? null : usage.inputTokens(),
          usage == null ? null : usage.cachedInputTokens(), usage == null ? null : usage.outputTokens(),
          cost(usage), usage == null ? null : usage.latencyMs(), startedAt, completedAt);
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
             prompt_version, model_route, model_id, status, error_code, request_digest,
             proposal_digest, input_tokens, cached_input_tokens, output_tokens, estimated_cost_usd,
             latency_ms, started_at, completed_at
        FROM core.ai_execution
      """;

  private static final RowMapper<AiExecution> MAPPER = (r, n) -> new AiExecution(
      r.getObject("id", UUID.class), r.getString("request_id"), r.getString("interaction_id"),
      r.getString("agent_type"), r.getString("contract_version"), r.getString("agent_version"),
      r.getString("prompt_version"), r.getString("model_route"), r.getString("model_id"),
      r.getString("status"), r.getString("error_code"), r.getString("request_digest"),
      r.getString("proposal_digest"), (Integer) r.getObject("input_tokens"),
      (Integer) r.getObject("cached_input_tokens"), (Integer) r.getObject("output_tokens"),
      r.getObject("estimated_cost_usd", BigDecimal.class), (Integer) r.getObject("latency_ms"),
      instant(r, "started_at"), instant(r, "completed_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private record ExecutionStart(String requestDigest) {}

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
    if ("FAILED".equals(attemptedStatus)
        && existing.errorCode() != null
        && !existing.errorCode().equals(attemptedErrorCode)) {
      throw new AiExecutionConflictException("requestId was reused with a different failure code");
    }
  }

  private static void validateTerminalCompatibility(AiExecution existing, TerminalEvent event) {
    validateTerminalCompatibility(existing, event.status(), event.errorCode(), event.requestDigest(),
        event.proposalDigest());
  }

  public static class AiExecutionConflictException extends RuntimeException {
    public AiExecutionConflictException(String message) { super(message); }
  }
}
