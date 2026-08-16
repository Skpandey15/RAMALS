package io.ramals.learningplatform.ai.contract;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Proposal envelope returned by the AI execution plane.
 *
 * <p>{@code confidence} is the agent's self-report and is never a basis for authority: deterministic
 * policy decides, and a high value here changes nothing.
 *
 * <p>{@code proposal} is deliberately open at contract v1.0 so each agent defines its own payload in
 * its own task without a breaking contract change. Each agent's shape is pinned by its own golden
 * fixtures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiProposalEnvelope(
    String contractVersion,
    String proposalId,
    AgentType agentType,
    String agentVersion,
    String promptVersion,
    String modelRoute,
    TrustLevel trustLevel,
    String confidence,
    List<String> reasonCodes,
    Map<String, Object> proposal,
    Validation validation,
    Usage usage) {
}
