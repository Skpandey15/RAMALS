package io.ramals.learningplatform.assessmentevaluation;

/** Raised when a stable evaluation request identity is reused for different decision content. */
public class AssessmentEvaluationReplayConflictException extends RuntimeException {
  public AssessmentEvaluationReplayConflictException(String message) {
    super(message);
  }
}
