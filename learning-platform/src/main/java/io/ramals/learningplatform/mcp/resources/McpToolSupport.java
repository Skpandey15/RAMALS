package io.ramals.learningplatform.mcp.resources;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextException;
import io.ramals.learningplatform.mcp.authorization.McpAuthorizationException;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * MCP-2: shared, deliberately thin plumbing every learner-scoped MCP tool handler uses -- argument
 * extraction, the uniform fail-closed error mapping, and capability-scoped telemetry. Contains no
 * authorization decision and no business logic of its own; {@code
 * io.ramals.learningplatform.mcp.authorization.McpCapabilityAuthorization} makes every authorization
 * decision, and each authoritative Java read service makes every business decision.
 *
 * <p>Every denial path logs a stable reason code and safe context only -- interactionId, traceId,
 * capability name, outcome -- never the raw delegated-context token, never a learner identifier
 * (opaque or otherwise), never a stack trace, and never enough detail to distinguish "wrong learner"
 * from "does not exist" for cross-learner attempts (M2-ADR-031, IDOR-avoidance).
 */
final class McpToolSupport {

  private static final Logger log = LoggerFactory.getLogger(McpToolSupport.class);

  private McpToolSupport() {
  }

  /** The raw delegated-context token argument, or {@code null} if absent/not a string -- {@code
   * McpCapabilityAuthorization#authorizeCapability} maps a {@code null} token to the same {@code
   * MISSING} reason {@link io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator}
   * already uses, so there is no separate "absent" code to keep consistent with it. */
  static String delegatedContextToken(Map<String, Object> arguments) {
    Object raw = arguments == null ? null : arguments.get("delegatedContext");
    return raw instanceof String token ? token : null;
  }

  /** A required string argument, or {@code null} if absent/blank/not a string -- callers treat a
   * {@code null} result as {@link McpAuthorizationException.Reason#MALFORMED_REQUEST}. */
  static String requiredString(Map<String, Object> arguments, String field) {
    Object raw = arguments == null ? null : arguments.get(field);
    return raw instanceof String value && !value.isBlank() ? value : null;
  }

  /**
   * Runs one tool call, mapping every governed failure to a non-leaking {@code CallToolResult} and
   * logging exactly the safe fields M2-ADR-031 requires. {@code body} returns the tool's own
   * successful {@code CallToolResult} (typically wrapping a mapped MCP DTO as {@code
   * structuredContent}).
   */
  static CallToolResult run(String capabilityName, Supplier<CallToolResult> body) {
    long startedAtNanos = System.nanoTime();
    try {
      CallToolResult result = body.get();
      logOutcome(capabilityName, "SUCCESS", null, startedAtNanos);
      return result;
    } catch (DelegatedLearnerContextException tokenFailure) {
      logOutcome(capabilityName, "DENIED", tokenFailure.reason().name(), startedAtNanos);
      return denied(tokenFailure.reason().name());
    } catch (McpAuthorizationException authorizationFailure) {
      logOutcome(capabilityName, "DENIED", authorizationFailure.reason().name(), startedAtNanos);
      return denied(authorizationFailure.reason().name());
    } catch (RuntimeException unexpected) {
      // Fail closed on anything unanticipated too -- never a stack trace or exception message to
      // the caller, which for a JDBC/mapping failure could otherwise echo query or schema detail.
      log.error(
          "MCP capability call failed unexpectedly capability={} interactionId={} traceId={}",
          capabilityName, CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId(),
          unexpected);
      logOutcome(capabilityName, "ERROR", "INTERNAL_ERROR", startedAtNanos);
      return denied("INTERNAL_ERROR");
    }
  }

  /** A successful call: {@code dto} becomes the result's structured content. */
  static CallToolResult success(Object dto) {
    return CallToolResult.builder().structuredContent(dto).isError(false).build();
  }

  /** A denied/failed call: only the stable reason code is ever disclosed. */
  static CallToolResult denied(String reasonCode) {
    return CallToolResult.builder().addTextContent(reasonCode).isError(true).build();
  }

  private static void logOutcome(
      String capabilityName, String outcome, String reasonCode, long startedAtNanos) {
    long latencyMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;
    try (var ignored = MDC.putCloseable("mcpCapability", capabilityName)) {
      log.info(
          "MCP capability call capability={} outcome={} reasonCode={} latencyMs={} "
              + "interactionId={} traceId={}",
          capabilityName, outcome, reasonCode, latencyMillis,
          CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
    }
  }
}
