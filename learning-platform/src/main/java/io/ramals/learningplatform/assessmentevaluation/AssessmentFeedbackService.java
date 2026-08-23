package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedback.ApprovedFeedback;
import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedback.RubricResult;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the minimized learner payload from the latest subject-owned gate decision. */
@Service
public class AssessmentFeedbackService {

  private static final int MAX_SUBJECT_LENGTH = 255;
  private static final int MAX_IDENTIFIER_LENGTH = 64;
  private static final int MAX_FEEDBACK_LENGTH = 4_000;
  private static final int MAX_RUBRIC_RESULTS = 32;

  private final AssessmentFeedbackRepository repository;

  public AssessmentFeedbackService(AssessmentFeedbackRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public AssessmentFeedback latest(String subject) {
    if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
      throw new IllegalArgumentException("a bounded authenticated subject is required");
    }
    return repository
        .findLatestForSubject(subject)
        .map(AssessmentFeedbackService::present)
        .orElseGet(
            () -> new AssessmentFeedback(AssessmentFeedbackStatus.UNAVAILABLE, null));
  }

  private static AssessmentFeedback present(AssessmentFeedbackReadModel decision) {
    return switch (decision.outcome()) {
      case "ACCEPTED" -> accepted(decision);
      case "REJECTED" -> new AssessmentFeedback(AssessmentFeedbackStatus.REJECTED, null);
      case "MANUAL_REVIEW" ->
          new AssessmentFeedback(AssessmentFeedbackStatus.MANUAL_REVIEW, null);
      default -> new AssessmentFeedback(AssessmentFeedbackStatus.UNAVAILABLE, null);
    };
  }

  private static AssessmentFeedback accepted(AssessmentFeedbackReadModel decision) {
    if (!validAcceptedProjection(decision)) {
      return new AssessmentFeedback(AssessmentFeedbackStatus.UNAVAILABLE, null);
    }
    List<RubricResult> results =
        decision.rubricResults().stream()
            .map(
                result ->
                    new RubricResult(
                        result.dimensionId(),
                        result.score(),
                        result.maxScore(),
                        result.feedback()))
            .toList();
    ApprovedFeedback approved =
        new ApprovedFeedback(
            decision.answerVersion(),
            decision.rubricVersion(),
            decision.feedback(),
            results,
            nextLearningRationale(results),
            decision.evaluatedAt());
    return new AssessmentFeedback(AssessmentFeedbackStatus.EVALUATED, approved);
  }

  private static String nextLearningRationale(List<RubricResult> results) {
    RubricResult focus =
        results.stream()
            .filter(AssessmentFeedbackService::validRatio)
            .min(
                Comparator.comparing(AssessmentFeedbackService::ratio)
                    .thenComparing(RubricResult::dimensionId))
            .orElse(null);
    if (focus == null) {
      return "Review the approved feedback before choosing your next learning activity.";
    }
    if (ratio(focus).compareTo(BigDecimal.ONE) >= 0) {
      return "You met every rubric expectation; continue with the next learning activity.";
    }
    return "Focus next on "
        + focus.dimensionId()
        + "; it has the greatest remaining opportunity in this evaluation.";
  }

  private static boolean validRatio(RubricResult result) {
    return result.dimensionId() != null
        && !result.dimensionId().isBlank()
        && result.score() != null
        && result.maxScore() != null
        && result.maxScore().signum() > 0;
  }

  private static boolean validAcceptedProjection(AssessmentFeedbackReadModel decision) {
    return bounded(decision.answerVersion(), MAX_IDENTIFIER_LENGTH)
        && bounded(decision.rubricVersion(), MAX_IDENTIFIER_LENGTH)
        && bounded(decision.feedback(), MAX_FEEDBACK_LENGTH)
        && decision.evaluatedAt() != null
        && !decision.rubricResults().isEmpty()
        && decision.rubricResults().size() <= MAX_RUBRIC_RESULTS
        && decision.rubricResults().stream().allMatch(AssessmentFeedbackService::validRubricResult);
  }

  private static boolean validRubricResult(
      AssessmentFeedbackReadModel.RubricResult result) {
    return result != null
        && bounded(result.dimensionId(), MAX_IDENTIFIER_LENGTH)
        && bounded(result.feedback(), MAX_FEEDBACK_LENGTH)
        && result.score() != null
        && result.maxScore() != null
        && result.score().signum() >= 0
        && result.maxScore().signum() > 0
        && result.score().compareTo(result.maxScore()) <= 0;
  }

  private static boolean bounded(String value, int maxLength) {
    return value != null && !value.isBlank() && value.length() <= maxLength;
  }

  private static BigDecimal ratio(RubricResult result) {
    return result.score().divide(result.maxScore(), 8, java.math.RoundingMode.HALF_UP);
  }
}
