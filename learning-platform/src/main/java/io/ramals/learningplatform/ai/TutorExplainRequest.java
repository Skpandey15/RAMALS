package io.ramals.learningplatform.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * What the learner is asking to have explained.
 *
 * <p>The learner is not named here and cannot be. The subject comes from the validated token, so a
 * caller cannot ask for an explanation "as" somebody else by putting a different identifier in the
 * body — the same reason every other learner-scoped endpoint takes its subject from
 * {@code Authentication} rather than from the request.
 */
public record TutorExplainRequest(
    @NotBlank(message = "skillCode is required") String skillCode,
    @NotBlank(message = "masteryStatus is required") String masteryStatus) {}
