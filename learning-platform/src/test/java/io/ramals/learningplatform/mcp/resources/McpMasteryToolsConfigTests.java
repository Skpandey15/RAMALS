package io.ramals.learningplatform.mcp.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
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

/** MCP-2: {@code mastery.current} -- real authorization layer, {@link MasteryMapService} mocked. The
 * delegated-context token is supplied via the exchange's transport context, never a tool argument. */
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
    McpSyncServerExchange exchange = McpTestExchanges.withDelegatedContextToken(token(learnerId, "KAFKA"));

    CallToolResult result = tool.callHandler().apply(exchange, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT, Map.of("domainCode", "KAFKA", "versionCode", "v1")));

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
    MasteryMapService service = mock(MasteryMapService.class);
    SyncToolSpecification tool = new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), service);
    McpSyncServerExchange exchange = McpTestExchanges.withDelegatedContextToken(token(learnerId, "KAFKA"));

    CallToolResult result = tool.callHandler().apply(exchange, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT, Map.of("domainCode", "CBSE", "versionCode", "v1")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("DOMAIN_MISMATCH");
    verifyNoInteractions(service);
  }

  /** Canonical uppercase domain comparison (security-review fix): "kafka" and "KAFKA" are the same
   * governed domain, matching H6/H7's own {@code toUpperCase(Locale.ROOT)} normalization -- not a
   * generic case-insensitive match. */
  @Test
  void domainComparisonIsCanonicalUppercaseNotArbitraryCase() {
    UUID learnerId = UUID.randomUUID();
    MasteryMapService service = mock(MasteryMapService.class);
    when(service.masteryMapForLearner(eq(learnerId), eq("kafka"), eq("v1"))).thenReturn(List.of());
    SyncToolSpecification tool = new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), service);
    // Delegated domain scope is "KAFKA" (issued below); request lower-cases it.
    McpSyncServerExchange exchange = McpTestExchanges.withDelegatedContextToken(token(learnerId, "KAFKA"));

    CallToolResult result = tool.callHandler().apply(exchange, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT, Map.of("domainCode", "kafka", "versionCode", "v1")));

    assertThat(result.isError()).isFalse();
  }

  @Test
  void masteryCurrentInputSchemaNeverContainsDelegatedContextOrLearnerIdentifiers() {
    SyncToolSpecification tool =
        new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), mock(MasteryMapService.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) tool.tool().inputSchema().get("properties");

    assertThat(properties).doesNotContainKeys("delegatedContext", "learnerId", "learnerRef");
    assertThat(properties).containsOnlyKeys("domainCode", "versionCode");
    assertThat(tool.tool().inputSchema().get("additionalProperties")).isEqualTo(false);
  }

  @Test
  void malformedRequestMissingVersionCodeIsRejected() {
    UUID learnerId = UUID.randomUUID();
    MasteryMapService service = mock(MasteryMapService.class);
    SyncToolSpecification tool = new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), service);
    McpSyncServerExchange exchange = McpTestExchanges.withDelegatedContextToken(token(learnerId, "KAFKA"));

    CallToolResult result = tool.callHandler().apply(exchange, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT, Map.of("domainCode", "KAFKA")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("MALFORMED_REQUEST");
    verifyNoInteractions(service);
  }

  @Test
  void missingTransportLevelDelegatedContextIsRejectedAndServiceIsNeverCalled() {
    MasteryMapService service = mock(MasteryMapService.class);
    SyncToolSpecification tool = new McpMasteryToolsConfig().mcpMasteryCurrentTool(authorization(), service);
    McpSyncServerExchange exchange = McpTestExchanges.withDelegatedContextToken(null);

    CallToolResult result = tool.callHandler().apply(exchange, new CallToolRequest(
        McpMasteryToolsConfig.MASTERY_CURRENT, Map.of("domainCode", "KAFKA", "versionCode", "v1")));

    assertThat(result.isError()).isTrue();
    assertThat(result.content().toString()).contains("MISSING");
    verifyNoInteractions(service);
  }
}
