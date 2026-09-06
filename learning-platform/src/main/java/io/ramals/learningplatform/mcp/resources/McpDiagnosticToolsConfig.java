package io.ramals.learningplatform.mcp.resources;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.ramals.learningplatform.assessment.DiagnosticReportService;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContext;
import io.ramals.learningplatform.mcp.authorization.McpAuthorizationException;
import io.ramals.learningplatform.mcp.authorization.McpAuthorizationException.Reason;
import io.ramals.learningplatform.mcp.authorization.McpCapabilityAuthorization;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP-2 (M2-ADR-031): registers exactly two H6 (M2-ADR-029) read-only capabilities --
 * {@code diagnostics.current-domain-report} and {@code diagnostics.attempt-report} -- as explicit MCP
 * tools. Both call {@link DiagnosticReportService} directly (the same authoritative read service
 * {@code DiagnosticReportController} calls); neither reaches {@code DiagnosticReportRepository},
 * {@code MisconceptionConfidenceRepository}, {@code MasteryRepository}, or {@code
 * DiagnosticConfidenceCalculatorV1} -- {@code ArchitectureGuardrailTests} already forbids the
 * repository dependencies structurally.
 *
 * <p>Registered as MCP <b>tools</b>, not resources: both take request-specific input (a domain code,
 * an attempt id), and MCP resources are meant for static, URI-addressable content rather than a
 * parameterized, per-caller-authorized read -- the same distinction the official SDK's own
 * tool/resource split exists to express. Read-only in effect despite being a "tool": neither handler
 * below can reach a mutation.
 *
 * <p><b>Neither tool's input schema declares a {@code delegatedContext} field.</b> The delegated
 * learner-context credential is an authorization credential, never a business lookup input a model
 * should choose or carry -- it arrives via a dedicated HTTP header ({@link
 * io.ramals.learningplatform.mcp.McpDelegatedContextTransportExtractor}) into the MCP exchange's own
 * transport context, and {@link McpToolSupport#delegatedContextToken} reads it from there. Tool
 * arguments here are business lookup inputs only ({@code domainCode}, {@code attemptId}).
 */
@Configuration
@ConditionalOnProperty(prefix = "ramals.mcp", name = "enabled", havingValue = "true")
public class McpDiagnosticToolsConfig {

  static final String CURRENT_DOMAIN_REPORT = "diagnostics.current-domain-report";
  static final String ATTEMPT_REPORT = "diagnostics.attempt-report";

  @Bean
  SyncToolSpecification mcpDiagnosticsCurrentDomainReportTool(
      McpCapabilityAuthorization authorization, DiagnosticReportService diagnosticReportService) {
    Tool tool = Tool.builder()
        .name(CURRENT_DOMAIN_REPORT)
        .title("Current domain diagnostic report")
        .description("The learner's complete current H6 diagnostic view for one domain: every "
            + "misconception with evidence, each at its own latest governed confidence state, plus "
            + "current mastery context. Read-only; never recomputes confidence. The caller is "
            + "identified solely by the workload's own delegated authorization, never by a request "
            + "argument.")
        .inputSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "domainCode", Map.of("type", "string",
                    "description", "The learning domain code, e.g. KAFKA.")),
            "required", List.of("domainCode"),
            "additionalProperties", false))
        .build();

    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> McpToolSupport.run(CURRENT_DOMAIN_REPORT, () -> {
          String delegatedContextToken =
              McpToolSupport.delegatedContextToken(exchange.transportContext());
          String domainCode = McpToolSupport.requiredString(request.arguments(), "domainCode");
          if (domainCode == null) {
            throw new McpAuthorizationException(Reason.MALFORMED_REQUEST);
          }

          DelegatedLearnerContext context =
              authorization.authorizeCapability(delegatedContextToken, CURRENT_DOMAIN_REPORT);
          authorization.authorizeDomain(context, domainCode);
          var learnerId = authorization.resolveLearnerId(context);

          var report = diagnosticReportService.currentDomainReportForLearner(learnerId, domainCode);
          return McpToolSupport.success(McpDiagnosticMapper.toMcp(report));
        }))
        .build();
  }

  @Bean
  SyncToolSpecification mcpDiagnosticsAttemptReportTool(
      McpCapabilityAuthorization authorization, DiagnosticReportService diagnosticReportService) {
    Tool tool = Tool.builder()
        .name(ATTEMPT_REPORT)
        .title("Attempt diagnostic report")
        .description("The exact-attempt H6 diagnostic findings one specific assessment attempt "
            + "produced -- never 'learner state as of this attempt', never mastery, never H5. "
            + "Read-only; never recomputes confidence. The caller is identified solely by the "
            + "workload's own delegated authorization, never by a request argument.")
        .inputSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "attemptId", Map.of("type", "string",
                    "description", "The assessment attempt id.")),
            "required", List.of("attemptId"),
            "additionalProperties", false))
        .build();

    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> McpToolSupport.run(ATTEMPT_REPORT, () -> {
          String delegatedContextToken =
              McpToolSupport.delegatedContextToken(exchange.transportContext());
          String attemptId = McpToolSupport.requiredString(request.arguments(), "attemptId");
          if (attemptId == null) {
            throw new McpAuthorizationException(Reason.MALFORMED_REQUEST);
          }

          DelegatedLearnerContext context =
              authorization.authorizeCapability(delegatedContextToken, ATTEMPT_REPORT);
          var learnerId = authorization.resolveLearnerId(context);

          // attemptReportForLearner itself refuses an attempt that exists but is not owned by
          // learnerId, identically to "does not exist" -- the same non-disclosure convention
          // DiagnosticReportController already relies on. Mapped to ATTEMPT_NOT_OWNED here rather
          // than left as a raw exception, so this call site's failure vocabulary stays within
          // McpAuthorizationException/DelegatedLearnerContextException, never a bare service
          // exception leaking to the transport layer.
          io.ramals.learningplatform.assessment.DiagnosticReport report;
          try {
            report = diagnosticReportService.attemptReportForLearner(learnerId, attemptId);
          } catch (io.ramals.learningplatform.assessment.AttemptNotFoundException notOwnedOrAbsent) {
            throw new McpAuthorizationException(Reason.ATTEMPT_NOT_OWNED);
          }
          return McpToolSupport.success(McpDiagnosticMapper.toMcp(report));
        }))
        .build();
  }
}
