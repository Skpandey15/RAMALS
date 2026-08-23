package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.ramals.learningplatform.grounding.GroundedContext;

/**
 * M2-T11 request for proposal-only rubric evaluation.
 *
 * <p>Deterministically scored response types are absent from {@link AiEvaluatedResponseType}, so a
 * caller cannot move them to the AI path through a mode flag. The learner identity remains the
 * opaque reference inside the grounded context.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentEvaluationRequest(
    String contractVersion,
    String interactionId,
    String requestId,
    Constraints constraints,
    AssessmentEvaluationContext evaluationContext,
    GroundedContext groundedContext) {

  public static final String CONTRACT_VERSION = "1.0";
}
