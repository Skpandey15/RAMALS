package io.ramals.learningplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.mastery.MasteryStatus;
import org.junit.jupiter.api.Test;

class ProgressionPolicyTests {

  private final ProgressionPolicy policy = new ProgressionPolicy();

  @Test
  void masteredStaysMasteredEvenWhenPrerequisitesAreNotReady() {
    ProgressionOutcome outcome = policy.decide(MasteryStatus.MASTERED, false, false, true);
    assertThat(outcome.state()).isEqualTo(ProgressionState.MASTERED);
    assertThat(outcome.reasonCode()).isEqualTo("MASTERED");
  }

  @Test
  void masteredBecomesRetentionDueWhenRetentionIsDue() {
    ProgressionOutcome outcome = policy.decide(MasteryStatus.MASTERED, true, true, false);
    assertThat(outcome.state()).isEqualTo(ProgressionState.RETENTION_DUE);
    assertThat(outcome.reasonCode()).isEqualTo("RETENTION_DUE");
  }

  @Test
  void notReadyPrerequisitesLockWithPlainBlockReason() {
    ProgressionOutcome outcome = policy.decide(null, false, false, false);
    assertThat(outcome.state()).isEqualTo(ProgressionState.LOCKED);
    assertThat(outcome.reasonCode()).isEqualTo("PREREQUISITE_BLOCKED");
  }

  @Test
  void regressedPrerequisiteLocksWithRemediationReason() {
    ProgressionOutcome outcome = policy.decide(MasteryStatus.NEEDS_PRACTICE, false, false, true);
    assertThat(outcome.state()).isEqualTo(ProgressionState.LOCKED);
    assertThat(outcome.reasonCode()).isEqualTo("REMEDIATION_REQUIRED");
  }

  @Test
  void readyPrerequisitesWithoutEvidenceAreEligible() {
    assertThat(policy.decide(null, true, false, false).state()).isEqualTo(ProgressionState.ELIGIBLE);
    assertThat(policy.decide(MasteryStatus.INSUFFICIENT_EVIDENCE, true, false, false).reasonCode())
        .isEqualTo("PREREQUISITE_READY");
  }

  @Test
  void readyPrerequisitesWithPartialMasteryNeedPractice() {
    assertThat(policy.decide(MasteryStatus.NEEDS_RETEACH, true, false, false))
        .isEqualTo(new ProgressionOutcome(ProgressionState.NEEDS_PRACTICE, "NEEDS_RETEACH"));
    assertThat(policy.decide(MasteryStatus.NEEDS_PRACTICE, true, false, false))
        .isEqualTo(new ProgressionOutcome(ProgressionState.NEEDS_PRACTICE, "NEEDS_PRACTICE"));
    assertThat(policy.decide(MasteryStatus.DEVELOPING, true, false, false))
        .isEqualTo(new ProgressionOutcome(ProgressionState.NEEDS_PRACTICE, "APPROACHING_MASTERY"));
  }
}
