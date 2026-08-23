package io.ramals.learningplatform.ai.contract;

import java.util.List;

/** Bounded learner answer and approved rubric assembled by the deterministic core. */
public record AssessmentEvaluationContext(
    AiEvaluatedResponseType responseType,
    String answerVersion,
    String rubricVersion,
    String answerEvidenceId,
    String answerText,
    List<AssessmentRubricDimension> rubricDimensions) {}
