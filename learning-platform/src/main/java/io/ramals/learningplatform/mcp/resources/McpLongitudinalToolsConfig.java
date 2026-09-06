package io.ramals.learningplatform.mcp.resources;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceService;
import io.ramals.learningplatform.assessment.MisconceptionNotFoundException;
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
 * MCP-2 (M2-ADR-031): registers exactly two H7 (M2-ADR-030) read-only capabilities --
 * {@code diagnostics.longitudinal-summary} and {@code diagnostics.misconception-longitudinal-detail}
 * -- as explicit MCP tools, both calling {@link LongitudinalEvidenceService} directly. Neither
 * recomputes a baseline, a classification, or G3 confidence; every {@code LongitudinalEvidenceState}
 * and {@code LongitudinalDataStatus} value is preserved verbatim, by name.
 *
 * <p>{@code misconception-longitudinal-detail} takes no domain parameter and enforces none: H7's own
 * {@code misconceptionDetailForLearner} API is scoped by {@code (learnerId, misconceptionId)} only,
 * with no domain argument in its own contract -- inventing a second, MCP-only domain check here
 * would be exactly the "second interpretation" MCP-2 is required not to invent. Learner isolation is
 * still absolute: the learner id is always the one {@link McpCapabilityAuthorization#resolveLearnerId}
 * resolves, so two different delegated contexts calling the same misconceptionId each see only their
 * own scoped state.
 *
 * <p><b>Neither tool's input schema declares a {@code delegatedContext} field</b> -- it arrives via a
 * dedicated HTTP header into the MCP exchange's own transport context, never a tool argument. See
 * {@link McpDiagnosticToolsConfig}'s own javadoc for the full rationale.
 */
@Configuration
@ConditionalOnProperty(prefix = "ramals.mcp", name = "enabled", havingValue = "true")
public class McpLongitudinalToolsConfig {

  static final String LONGITUDINAL_SUMMARY = "diagnostics.longitudinal-summary";
  static final String MISCONCEPTION_LONGITUDINAL_DETAIL = "diagnostics.misconception-longitudinal-detail";

  @Bean
  SyncToolSpecification mcpLongitudinalSummaryTool(
      McpCapabilityAuthorization authorization, LongitudinalEvidenceService longitudinalEvidenceService) {
    Tool tool = Tool.builder()
        .name(LONGITUDINAL_SUMMARY)
        .title("Longitudinal evidence domain summary")
        .description("Every misconception with an H7 baseline in one domain, and the "
            + "LONGITUDINAL_EVIDENCE_V1 classification of evidence recorded after each baseline. "
            + "Read-only; never recomputes a baseline or classification.")
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
        .callHandler((exchange, request) -> McpToolSupport.run(LONGITUDINAL_SUMMARY, () -> {
          String delegatedContextToken =
              McpToolSupport.delegatedContextToken(exchange.transportContext());
          String domainCode = McpToolSupport.requiredString(request.arguments(), "domainCode");
          if (domainCode == null) {
            throw new McpAuthorizationException(Reason.MALFORMED_REQUEST);
          }

          DelegatedLearnerContext context =
              authorization.authorizeCapability(delegatedContextToken, LONGITUDINAL_SUMMARY);
          authorization.authorizeDomain(context, domainCode);
          UUID learnerId = authorization.resolveLearnerId(context);

          var report = longitudinalEvidenceService.domainSummaryForLearner(learnerId, domainCode);
          return McpToolSupport.success(McpLongitudinalMapper.toMcp(report));
        }))
        .build();
  }

  @Bean
  SyncToolSpecification mcpLongitudinalDetailTool(
      McpCapabilityAuthorization authorization, LongitudinalEvidenceService longitudinalEvidenceService) {
    Tool tool = Tool.builder()
        .name(MISCONCEPTION_LONGITUDINAL_DETAIL)
        .title("Misconception longitudinal detail")
        .description("The H7 longitudinal projection for exactly one misconception -- present, with "
            + "dataStatus NO_BASELINE, even when the learner has no eligible baseline yet. Read-only; "
            + "never recomputes a baseline, classification, or G3 confidence.")
        .inputSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "misconceptionId", Map.of("type", "string",
                    "description", "The misconception id.")),
            "required", List.of("misconceptionId"),
            "additionalProperties", false))
        .build();

    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> McpToolSupport.run(MISCONCEPTION_LONGITUDINAL_DETAIL, () -> {
          String delegatedContextToken =
              McpToolSupport.delegatedContextToken(exchange.transportContext());
          String misconceptionId = McpToolSupport.requiredString(request.arguments(), "misconceptionId");
          if (misconceptionId == null) {
            throw new McpAuthorizationException(Reason.MALFORMED_REQUEST);
          }

          DelegatedLearnerContext context = authorization.authorizeCapability(
              delegatedContextToken, MISCONCEPTION_LONGITUDINAL_DETAIL);
          UUID learnerId = authorization.resolveLearnerId(context);

          io.ramals.learningplatform.assessment.LongitudinalEvidenceReport report;
          try {
            report = longitudinalEvidenceService.misconceptionDetailForLearner(learnerId, misconceptionId);
          } catch (MisconceptionNotFoundException notFound) {
            throw new McpAuthorizationException(Reason.MISCONCEPTION_NOT_ACCESSIBLE);
          }
          return McpToolSupport.success(McpLongitudinalMapper.toMcp(report));
        }))
        .build();
  }
}
