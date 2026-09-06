package io.ramals.learningplatform.mcp.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.ramals.learningplatform.assessment.AttemptNotFoundException;
import io.ramals.learningplatform.assessment.DiagnosticReport;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceState;
import io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus;
import io.ramals.learningplatform.assessment.DiagnosticReport.ReportMode;
import io.ramals.learningplatform.assessment.DiagnosticReportService;
import io.ramals.learningplatform.mcp.McpCapabilityRegistry;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator;
import io.ramals.learningplatform.mcp.authorization.McpCapabilityAuthorization;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MCP-2: {@code diagnostics.current-domain-report}/{@code diagnostics.attempt-report} tool handlers,
 * exercised directly against their real {@link SyncToolSpecification} -- a real {@link
 * McpCapabilityAuthorization} (real registry, real validator/issuer) with {@link
 * DiagnosticReportService} mocked, so the security path is genuine and only the authoritative
 * H6 read itself is stubbed.
 */
class McpDiagnosticToolsConfigTests {

  private static final String ISSUER = "ramals-learning-platform";
  private static final String AUDIENCE = "ramals-mcp";
  private static final byte[] KEY = randomKey();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private McpCapabilityAuthorization authorization() {
    DelegatedLearnerContextValidator validator =
        new DelegatedLearnerContextValidator(ISSUER, AUDIENCE, Map.of("current", KEY), CLOCK);
    return new McpCapabilityAuthorization(McpCapabilityRegistry.mcp2(), validator);
  }

  private String token(UUID learnerId, String domain, String... capabilities) {
    return new DelegatedLearnerContextIssuer(ISSUER, AUDIENCE, Duration.ofMinutes(2), "current", KEY, CLOCK)
        .issue("interaction-1", learnerId.toString(), domain, Set.of(capabilities));
  }

  private DiagnosticReport emptyReport(ReportMode mode, UUID learnerId, String domainCode, UUID attemptId) {
    return new DiagnosticReport(mode, learnerId, domainCode, attemptId, Instant.now(),
        DiagnosticDataStatus.NO_EVIDENCE, List.of(), List.of());
  }

  // -- current-domain-report -------------------------------------------------------------------

  @Test
  void currentDomainReportSucceedsForAuthorizedLearnerAndDomain() {
    UUID learnerId = UUID.randomUUID();
    DiagnosticReportService service = mock(DiagnosticReportService.class);
    when(service.currentDomainReportForLearner(eq(learnerId), eq("KAFKA")))
        .thenReturn(emptyReport(ReportMode.CURRENT_DOMAIN, learnerId, "KAFKA", null));

    SyncToolSpecification tool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsCurrentDomainReportTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpDiagnosticToolsConfig.CURRENT_DOMAIN_REPORT,
        Map.of("delegatedContext", token(learnerId, "KAFKA", McpDiagnosticToolsConfig.CURRENT_DOMAIN_REPORT),
            "domainCode", "KAFKA")));

    assertThat(result.isError()).isFalse();
    assertThat(result.structuredContent()).isInstanceOf(McpDiagnosticReport.class);
    McpDiagnosticReport report = (McpDiagnosticReport) result.structuredContent();
    assertThat(report.reportMode()).isEqualTo("CURRENT_DOMAIN");
    assertThat(report.diagnosticDataStatus()).isEqualTo("NO_EVIDENCE");
    verify(service).currentDomainReportForLearner(learnerId, "KAFKA");
  }

  @Test
  void currentDomainReportDeniesACrossDomainRequest() {
    UUID learnerId = UUID.randomUUID();
    DiagnosticReportService service = mock(DiagnosticReportService.class);
    SyncToolSpecification tool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsCurrentDomainReportTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpDiagnosticToolsConfig.CURRENT_DOMAIN_REPORT,
        Map.of("delegatedContext", token(learnerId, "KAFKA", McpDiagnosticToolsConfig.CURRENT_DOMAIN_REPORT),
            "domainCode", "SPRING_SECURITY")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("DOMAIN_MISMATCH");
  }

  @Test
  void currentDomainReportInputSchemaNeverDeclaresLearnerIdOrLearnerRef() {
    SyncToolSpecification tool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsCurrentDomainReportTool(authorization(), mock(DiagnosticReportService.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) tool.tool().inputSchema().get("properties");

    assertThat(properties).doesNotContainKeys("learnerId", "learnerRef");
    assertThat(tool.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
  }

  @Test
  void currentDomainReportRejectsALearnerSuppliedIdViaAdditionalPropertiesFalse() {
    // additionalProperties:false is the protocol-level guarantee that a client-supplied learnerId
    // could never be read even if a caller tried to smuggle one in; this proves the schema itself
    // carries that guarantee rather than asserting it only informally in prose.
    SyncToolSpecification tool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsCurrentDomainReportTool(authorization(), mock(DiagnosticReportService.class));

    assertThat(tool.tool().inputSchema()).containsEntry("additionalProperties", false);
  }

  // -- attempt-report ---------------------------------------------------------------------------

  @Test
  void attemptReportSucceedsForAnOwnedAttempt() {
    UUID learnerId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    DiagnosticReportService service = mock(DiagnosticReportService.class);
    when(service.attemptReportForLearner(eq(learnerId), eq(attemptId.toString())))
        .thenReturn(emptyReport(ReportMode.ATTEMPT, learnerId, null, attemptId));

    SyncToolSpecification tool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsAttemptReportTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpDiagnosticToolsConfig.ATTEMPT_REPORT,
        Map.of("delegatedContext", token(learnerId, "KAFKA", McpDiagnosticToolsConfig.ATTEMPT_REPORT),
            "attemptId", attemptId.toString())));

    assertThat(result.isError()).isFalse();
    McpDiagnosticReport report = (McpDiagnosticReport) result.structuredContent();
    assertThat(report.reportMode()).isEqualTo("ATTEMPT");
    assertThat(report.mastery()).isEmpty(); // semantic regression: attempt report never carries mastery
  }

  /** Test #8 (MCP-2 review): an attempt belonging to a different learner cannot be read. {@code
   * DiagnosticReportService} itself throws {@link AttemptNotFoundException} identically whether the
   * attempt does not exist or belongs to someone else; the tool handler maps that, uniformly, to
   * {@code ATTEMPT_NOT_OWNED} -- never distinguishing the two to the caller. */
  @Test
  void attemptReportDeniesAnAttemptNotOwnedByTheResolvedLearner() {
    UUID learnerId = UUID.randomUUID();
    String attemptId = UUID.randomUUID().toString();
    DiagnosticReportService service = mock(DiagnosticReportService.class);
    when(service.attemptReportForLearner(eq(learnerId), eq(attemptId)))
        .thenThrow(new AttemptNotFoundException(attemptId));

    SyncToolSpecification tool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsAttemptReportTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpDiagnosticToolsConfig.ATTEMPT_REPORT,
        Map.of("delegatedContext", token(learnerId, "KAFKA", McpDiagnosticToolsConfig.ATTEMPT_REPORT),
            "attemptId", attemptId)));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("ATTEMPT_NOT_OWNED");
  }

  @Test
  void attemptReportInputSchemaNeverDeclaresLearnerIdOrLearnerRef() {
    SyncToolSpecification tool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsAttemptReportTool(authorization(), mock(DiagnosticReportService.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) tool.tool().inputSchema().get("properties");

    assertThat(properties).doesNotContainKeys("learnerId", "learnerRef");
    assertThat(tool.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
  }

  @Test
  void missingCapabilityIsDeniedForBothDiagnosticTools() {
    UUID learnerId = UUID.randomUUID();
    // Token only allowlists mastery.current -- neither diagnostic capability.
    String token = token(learnerId, "KAFKA", "mastery.current");
    SyncToolSpecification currentDomainTool = new McpDiagnosticToolsConfig()
        .mcpDiagnosticsCurrentDomainReportTool(authorization(), mock(DiagnosticReportService.class));

    CallToolResult result = currentDomainTool.callHandler().apply(null, new CallToolRequest(
        McpDiagnosticToolsConfig.CURRENT_DOMAIN_REPORT,
        Map.of("delegatedContext", token, "domainCode", "KAFKA")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("CAPABILITY_NOT_DELEGATED");
  }
}
