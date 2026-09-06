package io.ramals.learningplatform.mcp.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.ramals.learningplatform.mastery.MasteryMapEntry;
import io.ramals.learningplatform.mastery.MasteryMapService;
import io.ramals.learningplatform.mcp.McpCapabilityRegistry;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator;
import io.ramals.learningplatform.mcp.authorization.McpCapabilityAuthorization;
import java.math.BigDecimal;
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

/** MCP-2: {@code mastery.current} -- real authorization layer, {@link MasteryMapService} mocked. */
class McpMasteryToolsConfigTests {

  private static final String ISSUER = "ramals-learning-platform";
  private static final String AUDIENCE = "ramals-mcp";
  private static final byte[] KEY = randomKey();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
  private static final String CAPABILITY = "mastery.current";

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

  private String token(UUID learnerId, String domain) {
    return new DelegatedLearnerContextIssuer(ISSUER, AUDIENCE, Duration.ofMinutes(2), "current", KEY, CLOCK)
        .issue("interaction-1", learnerId.toString(), domain, Set.of(CAPABILITY));
  }

  @Test
  void masteryCurrentSucceedsAndNeverCalculatesAnything() {
    UUID learnerId = UUID.randomUUID();
    MasteryMapService service = mock(MasteryMapService.class);
    when(service.masteryMapForLearner(eq(learnerId), eq("KAFKA"), eq("v1")))
        .thenReturn(List.of(new MasteryMapEntry(
            "SKILL_A", new BigDecimal("0.75"), new BigDecimal("0.60"), "PROFICIENT", 3)));

    SyncToolSpecification tool = new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), service);

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT,
        Map.of("delegatedContext", token(learnerId, "KAFKA"), "domainCode", "KAFKA", "versionCode", "v1")));

    assertThat(result.isError()).isFalse();
    McpMasteryReport report = (McpMasteryReport) result.structuredContent();
    // Read back verbatim -- the exact score/status the mocked authoritative service returned,
    // proving nothing was recomputed in between.
    assertThat(report.skills()).containsExactly(
        new McpMasterySkill("SKILL_A", new BigDecimal("0.75"), new BigDecimal("0.60"), "PROFICIENT", 3));
  }

  @Test
  void masteryCurrentDeniesACrossDomainRequest() {
    UUID learnerId = UUID.randomUUID();
    SyncToolSpecification tool =
        new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), mock(MasteryMapService.class));

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT,
        Map.of("delegatedContext", token(learnerId, "KAFKA"), "domainCode", "CBSE", "versionCode", "v1")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("DOMAIN_MISMATCH");
  }

  @Test
  void masteryCurrentInputSchemaNeverDeclaresLearnerIdOrLearnerRef() {
    SyncToolSpecification tool =
        new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), mock(MasteryMapService.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) tool.tool().inputSchema().get("properties");

    assertThat(properties).doesNotContainKeys("learnerId", "learnerRef");
    assertThat(tool.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
  }

  @Test
  void malformedRequestMissingVersionCodeIsRejected() {
    UUID learnerId = UUID.randomUUID();
    SyncToolSpecification tool =
        new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), mock(MasteryMapService.class));

    CallToolResult result = tool.callHandler().apply(null, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT,
        Map.of("delegatedContext", token(learnerId, "KAFKA"), "domainCode", "KAFKA")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("MALFORMED_REQUEST");
  }
}
