package io.ramals.learningplatform.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Outcome;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The deterministic trigger and eligibility rules, proved without a database or a model. */
class LearningWorkflowPolicyTests {

  private static final BigDecimal HALF = new BigDecimal("0.5000");

  @Test
  void onlyAnAcceptedEvaluationMayStartAComposition() {
    assertThat(LearningWorkflowPolicy.evaluationEligible(Outcome.ACCEPTED, HALF).eligible()).isTrue();

    for (Outcome refused : new Outcome[] {Outcome.REJECTED, Outcome.MANUAL_REVIEW}) {
      var eligibility = LearningWorkflowPolicy.evaluationEligible(refused, HALF);
      assertThat(eligibility.eligible()).as("%s must not seed evidence", refused).isFalse();
      assertThat(eligibility.reasonCode()).isEqualTo("EVALUATION_NOT_ACCEPTED");
    }
    assertThat(LearningWorkflowPolicy.evaluationEligible(null, HALF).eligible()).isFalse();
  }

  @Test
  void anUnusableScoreCannotStartACompositionEvenWhenTheGateAccepted() {
    for (String score : new String[] {"-0.0001", "1.0001"}) {
      var eligibility =
          LearningWorkflowPolicy.evaluationEligible(Outcome.ACCEPTED, new BigDecimal(score));
      assertThat(eligibility.eligible()).as("score %s", score).isFalse();
      assertThat(eligibility.reasonCode()).isEqualTo("EVALUATION_SCORE_UNUSABLE");
    }
    assertThat(LearningWorkflowPolicy.evaluationEligible(Outcome.ACCEPTED, null).eligible()).isFalse();
  }

  @Test
  void g02_aMasteredLearnerDoesNotSpendADiagnosticCall() {
    var stopped = LearningWorkflowPolicy.diagnosisEligible(MasteryStatus.MASTERED);
    assertThat(stopped.eligible()).isFalse();
    assertThat(stopped.reasonCode()).isEqualTo("DIAGNOSIS_NOT_REQUIRED");

    for (MasteryStatus status : MasteryStatus.values()) {
      if (status != MasteryStatus.MASTERED) {
        assertThat(LearningWorkflowPolicy.diagnosisEligible(status).eligible())
            .as("%s should still be diagnosed", status)
            .isTrue();
      }
    }
  }

  @Test
  void g03_aRejectedDiagnosisCannotProduceAnAdaptation() {
    assertThat(LearningWorkflowPolicy.adaptationEligible(true).eligible()).isTrue();
    var stopped = LearningWorkflowPolicy.adaptationEligible(false);
    assertThat(stopped.eligible()).isFalse();
    assertThat(stopped.reasonCode()).isEqualTo("ADAPTATION_PROPOSAL_REJECTED");
  }

  @Test
  void g06_retriesAreBoundedByAttemptCountAlone() {
    assertThat(LearningWorkflowPolicy.mayRetry(0)).isTrue();
    assertThat(LearningWorkflowPolicy.mayRetry(LearningWorkflowPolicy.MAX_STEP_ATTEMPTS - 1)).isTrue();
    assertThat(LearningWorkflowPolicy.mayRetry(LearningWorkflowPolicy.MAX_STEP_ATTEMPTS)).isFalse();
    assertThat(LearningWorkflowPolicy.mayRetry(Integer.MAX_VALUE)).isFalse();
  }

  @Test
  void g06_stepOrderIsStrictlyForwardAndTerminates() {
    Step step = Step.first();
    int guard = 0;
    while (LearningWorkflowPolicy.next(step).isPresent()) {
      Step next = LearningWorkflowPolicy.next(step).orElseThrow();
      assertThat(next.index()).isGreaterThan(step.index());
      step = next;
      assertThat(++guard).isLessThanOrEqualTo(Step.values().length);
    }
    assertThat(step).isEqualTo(Step.ADAPT);
    assertThat(LearningWorkflowPolicy.next(Step.ADAPT)).isEmpty();
  }

  @Test
  void onlyTheTwoAgentStepsCarryARequestIdentity() {
    assertThat(Step.RECORD_EVALUATION_EVIDENCE.invokesAgent()).isFalse();
    assertThat(Step.RECOMPUTE_MASTERY.invokesAgent()).isFalse();
    assertThat(Step.DIAGNOSE.invokesAgent()).isTrue();
    assertThat(Step.ADAPT.invokesAgent()).isTrue();
  }

  @Test
  void theDeterministicStepsRunBeforeAnyAgentIsAsked() {
    // Authoritative learner state must be settled before a model is consulted, so a slow or failing
    // agent can never leave the evidence ledger half-written.
    for (Step step : Step.values()) {
      if (step.invokesAgent()) {
        assertThat(step.index())
            .as("%s must not precede a deterministic step", step)
            .isGreaterThan(Step.RECOMPUTE_MASTERY.index());
      }
    }
  }
}
