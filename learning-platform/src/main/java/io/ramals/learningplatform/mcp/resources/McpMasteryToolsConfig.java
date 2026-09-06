package io.ramals.learningplatform.mcp.resources;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.ramals.learningplatform.mastery.MasteryMapService;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContext;
import io.ramals.learningplatform.mcp.authorization.McpAuthorizationException;
import io.ramals.learningplatform.mcp.authorization.McpAuthorizationException.Reason;
import io.ramals.learningplatform.mcp.authorization.McpCapabilityAuthorization;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP-2 (M2-ADR-031): registers exactly one read-only capability, {@code mastery.current}, calling
 * {@link MasteryMapService#masteryMapForLearner} directly -- the same authoritative read model {@code
 * MasteryMapController} exposes, via the learnerId-taking overload added alongside this PR to mirror
 * H6/H7's own {@code ...ForLearner} convention. No mastery calculation, no progression eligibility,
 * and no inference from H6/H7 exists anywhere in this class.
 *
 * <p><b>This tool's input schema does not declare a {@code delegatedContext} field</b> -- it arrives
 * via a dedicated HTTP header into the MCP exchange's own transport context, never a tool argument.
 * See {@link McpDiagnosticToolsConfig}'s own javadoc for the full rationale.
 */
@Configuration
@ConditionalOnProperty(prefix = "ramals.mcp", name = "enabled", havingValue = "true")
public class McpMasteryToolsConfig {

  static final String MASTERY_CURRENT = "mastery.current";

  @Bean
  SyncToolSpecification mcpMasteryCurrentTool(
      McpCapabilityAuthorization authorization, MasteryMapService masteryMapService) {
    Tool tool = Tool.builder()
        .name(MASTERY_CURRENT)
        .title("Current mastery map")
        .description("The learner's latest mastery score, evidence confidence, and status per skill "
            + "in one curriculum version. Read-only: no mutation, no progression eligibility, no "
            + "calculation of any kind -- read back verbatim from the authoritative mastery read model.")
        .inputSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "domainCode", Map.of("type", "string",
                    "description", "The learning domain code, e.g. KAFKA."),
                "versionCode", Map.of("type", "string",
                    "description", "The curriculum version code.")),
            "required", List.of("domainCode", "versionCode"),
            "additionalProperties", false))
        .build();

    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> McpToolSupport.run(MASTERY_CURRENT, () -> {
          String delegatedContextToken =
              McpToolSupport.delegatedContextToken(exchange.transportContext());
          String domainCode = McpToolSupport.requiredString(request.arguments(), "domainCode");
          String versionCode = McpToolSupport.requiredString(request.arguments(), "versionCode");
          if (domainCode == null || versionCode == null) {
            throw new McpAuthorizationException(Reason.MALFORMED_REQUEST);
          }

          DelegatedLearnerContext context =
              authorization.authorizeCapability(delegatedContextToken, MASTERY_CURRENT);
          authorization.authorizeDomain(context, domainCode);
          UUID learnerId = authorization.resolveLearnerId(context);

          var skills = masteryMapService.masteryMapForLearner(learnerId, domainCode, versionCode);
          var mcpSkills = skills.stream()
              .map(entry -> new McpMasterySkill(
                  entry.skillCode(), entry.masteryScore(), entry.evidenceConfidence(),
                  entry.masteryStatus(), entry.aggregateVersion()))
              .toList();
          return McpToolSupport.success(new McpMasteryReport(domainCode, versionCode, mcpSkills));
        }))
        .build();
  }
}
