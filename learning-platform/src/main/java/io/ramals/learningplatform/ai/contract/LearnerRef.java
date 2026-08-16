package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Opaque learner reference. Never a database primary key, and never enough on its own to identify a
 * person outside the platform.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LearnerRef(String learnerRef, String locale) {
}
