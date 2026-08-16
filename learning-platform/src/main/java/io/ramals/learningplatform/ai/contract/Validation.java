package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What the AI plane checked before returning. Spring re-validates regardless of what this says. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Validation(Boolean schemaValid, Boolean semanticValid, Integer repairAttempts) {
}
