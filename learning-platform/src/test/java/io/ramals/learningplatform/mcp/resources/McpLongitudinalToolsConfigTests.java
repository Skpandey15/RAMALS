package io.ramals.learningplatform.mcp.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.ramals.learningplatform.assessment.LongitudinalDataStatus;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport.LongitudinalEvidenceFinding;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceService;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceState;
import io.ramals.learningplatform.assessment.MisconceptionNotFoundException;
import io.ramals.learningplatform.assessment.MisconceptionTargetType;
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
 * MCP-2: {@code diagnostics.longitudinal-summary}/{@code diagnostics.misconception-longitudinal-detail}
 * tool handlers -- real authorization layer, {@link LongitudinalEvidenceService} mocked. Also proves
 * the H7 semantic-regression requirements: every {@link LongitudinalDataStatus}/{@link
 * LongitudinalEvidenceState} value survives {@link McpLongitudinalMapper} unchanged, by name.
 */
class McpLongitudinalToolsConfigTests {

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

  private LongitudinalEvidenceFinding findingWithState(
      LongitudinalDataStatus dataStatus, LongitudinalEvidenceState state) {
    return new LongitudinalEvidenceFinding(
        UUID.randomUUID(), "Some misconception", "description", MisconceptionTargetType.LEARNING_OBJECTIVE,
        UUID.randomUUID(), null, null, null, dataStatus,
        dataStatus == LongitudinalDataStatus.NO_BASELINE ? null
            : new LongitudinalEvidenceReport.Baseline(
                UUID.randomUUID(), io.ramals.learningplatform.assessment.DiagnosticConfidenceBand.LOW, Instant.now()),
        state, new DiagnosticReport_EvidenceSummaryShim().value(), List.of(), null, null,
        dataStatus == LongitudinalDataStatus.NO_BASELINE ? null : "LONGITUDINAL_EVIDENCE_V1");
  }

  // small shim to avoid a long fully-qualified literal inline above
  private static final class DiagnosticReport_EvidenceSummaryShim {
    io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary value() {
      return new io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary(0, 0, 0);
    }
  }

  @Test
  void summarySucceedsForAuthorizedLearnerAndDomain() {
    UUID learnerId = UUID.randomUUID();
    LongitudinalEvidenceService service = mock(LongitudinalEvidenceService.class);
    when(service.domainSummaryForLearner(eq(learnerId), eq("KAFKA")))
        .thenReturn(new LongitudinalEvidenceReport(learnerId, "KAFKA", Instant.now(), List.of()));

    SyncToolSpecification tool =
        new McpLongitudinalToolsConfig().mcpLongitudinalSummaryTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpLongitudinalToolsConfig.LONGITUDINAL_SUMMARY,
        Map.of("delegatedContext", token(learnerId, "KAFKA", McpLongitudinalToolsConfig.LONGITUDINAL_SUMMARY),
            "domainCode", "KAFKA")));

    assertThat(result.isError()).isFalse();
    assertThat(((McpLongitudinalReport) result.structuredContent()).domainCode()).isEqualTo("KAFKA");
  }

  @Test
  void summaryDeniesACrossDomainRequest() {
    UUID learnerId = UUID.randomUUID();
    SyncToolSpecification tool = new McpLongitudinalToolsConfig()
        .mcpLongitudinalSummaryTool(authorization(), mock(LongitudinalEvidenceService.class));

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpLongitudinalToolsConfig.LONGITUDINAL_SUMMARY,
        Map.of("delegatedContext", token(learnerId, "KAFKA", McpLongitudinalToolsConfig.LONGITUDINAL_SUMMARY),
            "domainCode", "CBSE")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("DOMAIN_MISMATCH");
  }

  /** Semantic regression: every {@code LongitudinalDataStatus}/{@code LongitudinalEvidenceState}
   * value survives the mapper unchanged, by exact name -- never renamed to a stronger claim. */
  @Test
  void everyLongitudinalStateValueSurvivesMappingByExactName() {
    List<LongitudinalEvidenceFinding> findings = List.of(
        findingWithState(LongitudinalDataStatus.NO_BASELINE, null),
        findingWithState(LongitudinalDataStatus.HAS_BASELINE, LongitudinalEvidenceState.NO_LATER_EVIDENCE),
        findingWithState(LongitudinalDataStatus.HAS_BASELINE, LongitudinalEvidenceState.LATER_INCONCLUSIVE_ONLY),
        findingWithState(LongitudinalDataStatus.HAS_BASELINE, LongitudinalEvidenceState.LATER_SUPPORT_ONLY),
        findingWithState(LongitudinalDataStatus.HAS_BASELINE, LongitudinalEvidenceState.LATER_CONTRADICTION_ONLY),
        findingWithState(LongitudinalDataStatus.HAS_BASELINE, LongitudinalEvidenceState.LATER_MIXED_EVIDENCE));
    LongitudinalEvidenceReport report =
        new LongitudinalEvidenceReport(UUID.randomUUID(), "KAFKA", Instant.now(), findings);

    McpLongitudinalReport mapped = McpLongitudinalMapper.toMcp(report);

    assertThat(mapped.findings()).extracting(McpLongitudinalReport.Finding::dataStatus)
        .containsExactly("NO_BASELINE", "HAS_BASELINE", "HAS_BASELINE", "HAS_BASELINE", "HAS_BASELINE", "HAS_BASELINE");
    assertThat(mapped.findings()).extracting(McpLongitudinalReport.Finding::state)
        .containsExactly(null, "NO_LATER_EVIDENCE", "LATER_INCONCLUSIVE_ONLY", "LATER_SUPPORT_ONLY",
            "LATER_CONTRADICTION_ONLY", "LATER_MIXED_EVIDENCE");
  }

  // -- misconception-longitudinal-detail ---------------------------------------------------------

  @Test
  void detailSucceedsEvenWithNoBaseline() {
    UUID learnerId = UUID.randomUUID();
    String misconceptionId = UUID.randomUUID().toString();
    LongitudinalEvidenceService service = mock(LongitudinalEvidenceService.class);
    when(service.misconceptionDetailForLearner(eq(learnerId), eq(misconceptionId)))
        .thenReturn(new LongitudinalEvidenceReport(learnerId, null, Instant.now(),
            List.of(findingWithState(LongitudinalDataStatus.NO_BASELINE, null))));

    SyncToolSpecification tool =
        new McpLongitudinalToolsConfig().mcpLongitudinalDetailTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL,
        Map.of("delegatedContext",
            token(learnerId, "KAFKA", McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL),
            "misconceptionId", misconceptionId)));

    assertThat(result.isError()).isFalse();
    McpLongitudinalReport report = (McpLongitudinalReport) result.structuredContent();
    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().get(0).dataStatus()).isEqualTo("NO_BASELINE");
  }

  /** Test #9 (MCP-2 review): a nonexistent misconception is denied, never leaking whether it exists
   * for a different learner. */
  @Test
  void detailDeniesANonexistentMisconception() {
    UUID learnerId = UUID.randomUUID();
    String misconceptionId = UUID.randomUUID().toString();
    LongitudinalEvidenceService service = mock(LongitudinalEvidenceService.class);
    when(service.misconceptionDetailForLearner(eq(learnerId), eq(misconceptionId)))
        .thenThrow(new MisconceptionNotFoundException(misconceptionId));

    SyncToolSpecification tool =
        new McpLongitudinalToolsConfig().mcpLongitudinalDetailTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL,
        Map.of("delegatedContext",
            token(learnerId, "KAFKA", McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL),
            "misconceptionId", misconceptionId)));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("MISCONCEPTION_NOT_ACCESSIBLE");
  }

  /** Test #9's converse: two different learners' delegated contexts, the same misconceptionId --
   * each resolves against its OWN learnerId (never overridable), so each only ever sees its own
   * scoped state; there is no code path by which learner A's data could reach learner B's call. */
  @Test
  void twoLearnersCallingTheSameMisconceptionEachResolveTheirOwnLearnerId() {
    UUID learnerA = UUID.randomUUID();
    UUID learnerB = UUID.randomUUID();
    String misconceptionId = UUID.randomUUID().toString();
    LongitudinalEvidenceService service = mock(LongitudinalEvidenceService.class);
    when(service.misconceptionDetailForLearner(eq(learnerA), eq(misconceptionId)))
        .thenReturn(new LongitudinalEvidenceReport(learnerA, null, Instant.now(),
            List.of(findingWithState(LongitudinalDataStatus.HAS_BASELINE, LongitudinalEvidenceState.NO_LATER_EVIDENCE))));
    when(service.misconceptionDetailForLearner(eq(learnerB), eq(misconceptionId)))
        .thenReturn(new LongitudinalEvidenceReport(learnerB, null, Instant.now(),
            List.of(findingWithState(LongitudinalDataStatus.NO_BASELINE, null))));

    SyncToolSpecification tool =
        new McpLongitudinalToolsConfig().mcpLongitudinalDetailTool(authorization(), service);

    CallToolResult resultA = tool.callHandler().apply(null, new CallToolRequest(
        McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL,
        Map.of("delegatedContext",
            token(learnerA, "KAFKA", McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL),
            "misconceptionId", misconceptionId)));
    CallToolResult resultB = tool.callHandler().apply(null, new CallToolRequest(
        McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL,
        Map.of("delegatedContext",
            token(learnerB, "KAFKA", McpLongitudinalToolsConfig.MISCONCEPTION_LONGITUDINAL_DETAIL),
            "misconceptionId", misconceptionId)));

    assertThat(((McpLongitudinalReport) resultA.structuredContent()).findings().get(0).dataStatus())
        .isEqualTo("HAS_BASELINE");
    assertThat(((McpLongitudinalReport) resultB.structuredContent()).findings().get(0).dataStatus())
        .isEqualTo("NO_BASELINE");
  }
}
