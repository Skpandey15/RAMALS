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
    /**
     * The route configuration the AI plane is actually serving, pins included.
     *
     * <p>Nullable: a deployed plane that predates the field reports nothing, and a core that
     * refused to read such a response would turn a additive contract change into an outage.
     */
    String routeTableVersion,
    List<AgentType> agents,
    String authority) {
}
