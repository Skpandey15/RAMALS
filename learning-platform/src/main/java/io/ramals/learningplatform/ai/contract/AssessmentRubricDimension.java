package io.ramals.learningplatform.ai.contract;

import java.math.BigDecimal;

/** One versioned, approved rubric dimension bound to a grounded source fact. */
public record AssessmentRubricDimension(
    String dimensionId,
    BigDecimal maxScore,
    String criteria,
    String evidenceId) {}
