package io.ramals.learningplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LearningSessionPolicyTests {

  private final LearningSessionPolicy policy = new LearningSessionPolicy();

  @Test
  void activeTransitionsAreValid() {
    assertThat(policy.target(LearningSessionStatus.ACTIVE, LearningSessionCommand.PAUSE))
        .contains(LearningSessionStatus.PAUSED);
    assertThat(policy.target(LearningSessionStatus.ACTIVE, LearningSessionCommand.COMPLETE))
        .contains(LearningSessionStatus.COMPLETED);
    assertThat(policy.target(LearningSessionStatus.ACTIVE, LearningSessionCommand.ABANDON))
        .contains(LearningSessionStatus.ABANDONED);
    assertThat(policy.target(LearningSessionStatus.ACTIVE, LearningSessionCommand.RESUME)).isEmpty();
  }

  @Test
  void pausedTransitionsAreValid() {
    assertThat(policy.target(LearningSessionStatus.PAUSED, LearningSessionCommand.RESUME))
        .contains(LearningSessionStatus.ACTIVE);
    assertThat(policy.target(LearningSessionStatus.PAUSED, LearningSessionCommand.ABANDON))
        .contains(LearningSessionStatus.ABANDONED);
    assertThat(policy.target(LearningSessionStatus.PAUSED, LearningSessionCommand.PAUSE)).isEmpty();
    assertThat(policy.target(LearningSessionStatus.PAUSED, LearningSessionCommand.COMPLETE)).isEmpty();
  }

  @Test
  void terminalStatusesRejectEveryCommand() {
    for (LearningSessionCommand command : LearningSessionCommand.values()) {
      assertThat(policy.target(LearningSessionStatus.COMPLETED, command)).isEqualTo(Optional.empty());
      assertThat(policy.target(LearningSessionStatus.ABANDONED, command)).isEqualTo(Optional.empty());
    }
  }
}
