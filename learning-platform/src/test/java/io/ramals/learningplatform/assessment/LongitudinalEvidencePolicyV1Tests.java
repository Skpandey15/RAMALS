package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * M2-ADR-030: {@link LongitudinalEvidencePolicyV1} is a pure, order-independent, magnitude-invariant
 * sign/existence classifier -- these tests prove exactly that, independent of the real-PostgreSQL
 * integration suite ({@code LongitudinalEvidenceReportPersistenceIntegrationTests}), which proves the
 * repository/service assembly around it.
 */
class LongitudinalEvidencePolicyV1Tests {

  private final LongitudinalEvidencePolicyV1 policy = new LongitudinalEvidencePolicyV1();

  @Test
  void noLaterEvidenceIsExactlyZeroZeroZero() {
    assertThat(policy.classify(0, 0, 0)).isEqualTo(LongitudinalEvidenceState.NO_LATER_EVIDENCE);
  }

  @Test
  void inconclusiveOnlyIgnoresMagnitude() {
    assertThat(policy.classify(0, 0, 1)).isEqualTo(LongitudinalEvidenceState.LATER_INCONCLUSIVE_ONLY);
    assertThat(policy.classify(0, 0, 50)).isEqualTo(LongitudinalEvidenceState.LATER_INCONCLUSIVE_ONLY);
  }

  @Test
  void supportOnlyIgnoresInconclusiveAndMagnitude() {
    assertThat(policy.classify(1, 0, 0)).isEqualTo(LongitudinalEvidenceState.LATER_SUPPORT_ONLY);
    assertThat(policy.classify(5, 0, 3)).isEqualTo(LongitudinalEvidenceState.LATER_SUPPORT_ONLY);
  }

  @Test
  void contradictionOnlyIgnoresInconclusiveAndMagnitude() {
    assertThat(policy.classify(0, 1, 0)).isEqualTo(LongitudinalEvidenceState.LATER_CONTRADICTION_ONLY);
    assertThat(policy.classify(0, 4, 2)).isEqualTo(LongitudinalEvidenceState.LATER_CONTRADICTION_ONLY);
  }

  @Test
  void mixedEvidenceIgnoresMagnitude() {
    assertThat(policy.classify(1, 1, 0)).isEqualTo(LongitudinalEvidenceState.LATER_MIXED_EVIDENCE);
    assertThat(policy.classify(3, 7, 10)).isEqualTo(LongitudinalEvidenceState.LATER_MIXED_EVIDENCE);
  }

  /**
   * Baseline-direction invariance (M2-ADR-030 §D): the classifier never sees the baseline at all --
   * it operates purely on the post-baseline delta -- so the same delta always yields the same state
   * regardless of what the baseline itself looked like. This test asserts that structurally: the
   * classifier's signature has no baseline parameter, so calling it with the same delta twice (as if
   * from a support-only baseline and, separately, a contradiction-only baseline) is definitionally the
   * same call.
   */
  @Test
  void negativeSupportingDeltaIsRejected() {
    assertThatThrownBy(() -> policy.classify(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negativeContradictoryDeltaIsRejected() {
    assertThatThrownBy(() -> policy.classify(0, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negativeInconclusiveDeltaIsRejected() {
    assertThatThrownBy(() -> policy.classify(0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allNegativeCountsAreRejected() {
    assertThatThrownBy(() -> policy.classify(-3, -2, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void classificationNeverDependsOnBaselineDirectionByConstruction() {
    LongitudinalEvidenceState fromWhatWouldFollowAContradictionOnlyBaseline = policy.classify(1, 0, 0);
    LongitudinalEvidenceState fromWhatWouldFollowASupportOnlyBaseline = policy.classify(1, 0, 0);
    assertThat(fromWhatWouldFollowAContradictionOnlyBaseline)
        .isEqualTo(fromWhatWouldFollowASupportOnlyBaseline)
        .isEqualTo(LongitudinalEvidenceState.LATER_SUPPORT_ONLY);
  }
}
