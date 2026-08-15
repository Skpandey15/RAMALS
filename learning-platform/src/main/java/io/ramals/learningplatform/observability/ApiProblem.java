package io.ramals.learningplatform.observability;

public record ApiProblem(
    String type,
    String title,
    int status,
    String code,
    String detail,
    String interactionId,
    String traceId) {
}

