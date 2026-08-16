package io.ramals.learningplatform.ai.contract;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Bounds the callee must respect.
 *
 * <p>{@code deadlineMs} is the remaining budget at the moment of the call, not a per-hop timeout: a
 * retry consumes it rather than resetting it (M1-ADR-001).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Constraints(
    InteractionClass interactionClass,
    Integer deadlineMs,
    Integer maxOutputTokens,
    List<String> allowedTools,
    String policyVersion) {
}
