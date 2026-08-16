package io.ramals.learningplatform.ai.contract;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Operational capability advertisement from the AI plane. Carries no learner data. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCapabilities(
    String contractVersion,
    String service,
    String version,
    String environment,
    Boolean aiEnabled,
    String modelRoute,
    List<AgentType> agents,
    String authority) {
}
