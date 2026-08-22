package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.ramals.learningplatform.grounding.GroundedContext;

/**
 * The M2-T09 request for a grounded diagnostic assessment.
 *
 * <p>A separate contract from {@link AiRequestEnvelope} rather than a new optional field on it. The
 * grounded context is mandatory for this operation and meaningless for the MVP-1 operations, and an
 * optional field carrying that distinction would leave every existing operation with an ambiguous
 * "is it required for me?" semantic that no schema states. The additive path also keeps the frozen
 * v1 baseline intact.
 *
 * <p>Carries no learner reference of its own. The only learner identity that crosses is the opaque
 * one inside the context, which Spring resolved from the authenticated subject.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosticAssessmentRequest(
    String contractVersion,
    String interactionId,
    String requestId,
    Constraints constraints,
    GroundedContext groundedContext) {

  public static final String CONTRACT_VERSION = "1.0";
}
