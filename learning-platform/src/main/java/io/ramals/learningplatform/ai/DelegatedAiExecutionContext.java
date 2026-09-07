package io.ramals.learningplatform.ai;

import java.util.Optional;

/**
 * The delegated learner-context credential for one outbound Java→ramals-ai call (M2-ADR-031,
 * MCP-3), already minted -- or deliberately absent -- by an authoritative caller before an AI
 * client is ever invoked.
 *
 * <p>Deliberately not model-facing and not part of {@code AiRequestEnvelope}/{@code
 * DiagnosticAssessmentRequest}'s own business payload: it travels only as the dedicated {@code
 * X-Ramals-Delegated-Context} transport header (see {@code McpDelegatedContextTransportExtractor}),
 * never as JSON body content, a log field, or any value an agent or model could observe.
 *
 * @param token the signed, short-lived delegated-context JWT for this call, or empty when MCP
 *     delegation is not available (MCP disabled, no signing key configured, or minting failed
 *     closed for this specific call) -- absence means the outbound call simply carries no
 *     delegated-context header, exactly today's existing behavior, never a broader or default one.
 */
public record DelegatedAiExecutionContext(Optional<String> token) {

  /** No delegated context for this call. The safe default every existing caller gets. */
  public static final DelegatedAiExecutionContext NONE =
      new DelegatedAiExecutionContext(Optional.empty());

  public DelegatedAiExecutionContext {
    token = token == null ? Optional.empty() : token;
  }
}
