package io.ramals.learningplatform.assessmentevaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedbackReadModel.RubricResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AssessmentFeedbackServiceTests {

  private static final Instant EVALUATED_AT = Instant.parse("2026-08-23T00:00:00Z");

  private final AssessmentFeedbackRepository repository = mock(AssessmentFeedbackRepository.class);
  private final AssessmentFeedbackService service = new AssessmentFeedbackService(repository);

  @Test
  void acceptedDecisionExposesOnlyApprovedContentAndDeterministicNextStep() {
    when(repository.findLatestForSubject("learner-1"))
        .thenReturn(
            Optional.of(
                decision(
                    "ACCEPTED",
                    "Approved overall feedback.",
                    List.of(
                        result("reasoning", "3", "4", "Reasoning is nearly complete."),
                        result("accuracy", "1", "4", "Accuracy needs more precision.")))));

    AssessmentFeedback feedback = service.latest("learner-1");

    assertThat(feedback.status()).isEqualTo(AssessmentFeedbackStatus.EVALUATED);
    assertThat(feedback.approvedFeedback().feedback()).isEqualTo("Approved overall feedback.");
    assertThat(feedback.approvedFeedback().rubricResults())
        .extracting(AssessmentFeedback.RubricResult::dimensionId)
        .containsExactly("reasoning", "accuracy");
    assertThat(feedback.approvedFeedback().nextLearningRationale())
        .isEqualTo(
            "Focus next on accuracy; it has the greatest remaining opportunity in this evaluation.");
  }

  @Test
  void completeRubricRecommendsContinuingToTheNextActivity() {
    when(repository.findLatestForSubject("learner-1"))
        .thenReturn(
            Optional.of(
                decision(
                    "ACCEPTED",
                    "Approved.",
                    List.of(result("accuracy", "4", "4", "Complete.")))));

    assertThat(service.latest("learner-1").approvedFeedback().nextLearningRationale())
        .isEqualTo(
            "You met every rubric expectation; continue with the next learning activity.");
  }

  @Test
  void rejectedAndManualReviewDecisionsNeverExposeCandidateContent() {
    for (String outcome : List.of("REJECTED", "MANUAL_REVIEW")) {
      when(repository.findLatestForSubject("learner-1"))
          .thenReturn(
              Optional.of(
                  decision(
                      outcome,
                      "Unsafe candidate feedback must stay hidden.",
                      List.of(result("accuracy", "4", "4", "Hidden model detail.")))));

      AssessmentFeedback feedback = service.latest("learner-1");

      assertThat(feedback.status().name()).isEqualTo(outcome);
      assertThat(feedback.approvedFeedback()).isNull();
    }
  }

  @Test
  void absentOrMalformedAcceptedProjectionFailsClosedAsUnavailable() {
    when(repository.findLatestForSubject("learner-1"))
        .thenReturn(
            Optional.of(
                decision("ACCEPTED", null, List.of(result("accuracy", "4", "4", "OK")))));
    assertThat(service.latest("learner-1").status())
        .isEqualTo(AssessmentFeedbackStatus.UNAVAILABLE);

    when(repository.findLatestForSubject("learner-1"))
        .thenReturn(
            Optional.of(
                decision(
                    "ACCEPTED",
                    "Approved.",
                    List.of(result("accuracy", "5", "4", "Impossible stored score.")))));
    assertThat(service.latest("learner-1").status())
        .isEqualTo(AssessmentFeedbackStatus.UNAVAILABLE);

    when(repository.findLatestForSubject("learner-1")).thenReturn(Optional.empty());
    assertThat(service.latest("learner-1").status())
        .isEqualTo(AssessmentFeedbackStatus.UNAVAILABLE);
  }

  @Test
  void rejectsMissingOrUnboundedAuthenticatedSubjectBeforeRepositoryRead() {
    assertThatThrownBy(() -> service.latest(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.latest("x".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AssessmentFeedbackReadModel decision(
      String outcome, String feedback, List<RubricResult> results) {
    return new AssessmentFeedbackReadModel(
        outcome, "answer-v1", "rubric-v1", feedback, results, EVALUATED_AT);
  }

  private static RubricResult result(
      String dimension, String score, String maxScore, String feedback) {
    return new RubricResult(
        dimension, new BigDecimal(score), new BigDecimal(maxScore), feedback);
  }
}
