package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Request envelope sent to the AI execution plane. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiRequestEnvelope(
    String contractVersion,
    String interactionId,
    String requestId,
    LearnerRef learner,
    LearningContext learningContext,
    Constraints constraints,
    String requestedCapability) {

  public static final String CONTRACT_VERSION = "1.0";
}
