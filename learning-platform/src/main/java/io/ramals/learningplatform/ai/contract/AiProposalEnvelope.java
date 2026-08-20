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
    /**
     * The orchestrated agent execution that produced this proposal.
     *
     * <p>Distinct from {@code proposalId}, {@code requestId} and {@code interactionId}: a retried
     * request produces several runs, and one interaction may involve several agents. Carried across
     * the plane boundary so this side's log lines can name the run that proposed a decision;
     * without it the correlation chain ends exactly where a support pivot has to cross.
     *
     * <p>Nullable, because a deployed plane predating the field sends nothing and refusing such a
     * response would turn an additive contract change into an outage.
     */
    String agentRunId,
    /**
     * Which prompt produced this proposal (M1-ADR-011).
     *
     * <p>Two of the assessment agent's prompts share a version, so {@code promptVersion} alone does
     * not identify one. Nullable for the same reason as {@code agentRunId}.
     */
    String promptTemplateId,
    String promptVersion,
    String modelRoute,
    TrustLevel trustLevel,
    String confidence,
    List<String> reasonCodes,
    Map<String, Object> proposal,
    Validation validation,
    Usage usage) {
}
