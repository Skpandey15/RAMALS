package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link HypothesisConfirmationDiagnosticSelector}: the regression rule in isolation, and the
 * signal-map adjustment it drives. Every adjustment case asserts the class's own stated boundary --
 * reprioritise, never re-band.
 */
class HypothesisConfirmationDiagnosticSelectorTests {

  // -----------------------------------------------------------------------------------------
  // isRegression
  // -----------------------------------------------------------------------------------------

  @Test
  void masteredThenDevelopingIsARegression() {
    assertThat(HypothesisConfirmationDiagnosticSelector
        .isRegression(MasteryStatus.MASTERED, MasteryStatus.DEVELOPING)).isTrue();
  }

  @Test
  void developingThenMasteredIsNotARegressionItIsProgress() {
    assertThat(HypothesisConfirmationDiagnosticSelector
        .isRegression(MasteryStatus.DEVELOPING, MasteryStatus.MASTERED)).isFalse();
  }

  @Test
  void identicalConsecutiveStatusesAreNotARegression() {
    assertThat(HypothesisConfirmationDiagnosticSelector
        .isRegression(MasteryStatus.DEVELOPING, MasteryStatus.DEVELOPING)).isFalse();
  }

  @Test
  void insufficientEvidenceThenAnyRealStatusIsEvidenceArrivingNotARegression() {
    // Going from "unknown" to "known and weak" is the first evidence for this skill, not a
    // regression from anything -- excluded deliberately, not merely because ordinal(0) is lowest.
    assertThat(HypothesisConfirmationDiagnosticSelector
        .isRegression(MasteryStatus.INSUFFICIENT_EVIDENCE, MasteryStatus.NEEDS_RETEACH)).isFalse();
  }

  @Test
  void nullPreviousOrLatestIsNeverARegression() {
    assertThat(HypothesisConfirmationDiagnosticSelector.isRegression(null, MasteryStatus.DEVELOPING))
        .isFalse();
    assertThat(HypothesisConfirmationDiagnosticSelector.isRegression(MasteryStatus.MASTERED, null))
        .isFalse();
  }

  // -----------------------------------------------------------------------------------------
  // adjustForRegressions
  // -----------------------------------------------------------------------------------------

  @Test
  void aRegressedSkillIsReprioritisedButKeepsWhateverBandUpstreamAlreadyDecided() {
    // Simulates arriving already-capped by V3 (PREREQUISITE_NOT_SECURED at FOUNDATIONAL) -- V4
    // must not re-escalate it, only change the reason and priority.
    SkillMasterySignal capped = new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.PREREQUISITE_NOT_SECURED, 5);
    Map<String, SkillMasterySignal> base = Map.of("A", capped);

    Map<String, SkillMasterySignal> adjusted = HypothesisConfirmationDiagnosticSelector
        .adjustForRegressions(base, Set.of("A"));

    SkillMasterySignal result = adjusted.get("A");
    assertThat(result.targetDifficulty()).isEqualTo(AssessmentDifficulty.FOUNDATIONAL);
    assertThat(result.reason()).isEqualTo(SelectionReason.HYPOTHESIS_CONFIRMATION);
    assertThat(result.priority()).isZero();
  }

  @Test
  void aNonRegressedSkillPassesThroughUnchanged() {
    SkillMasterySignal progression = new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> base = Map.of("A", progression);

    Map<String, SkillMasterySignal> adjusted =
        HypothesisConfirmationDiagnosticSelector.adjustForRegressions(base, Set.of());

    assertThat(adjusted.get("A")).isSameAs(progression);
  }

  @Test
  void onlyTheRegressedSkillIsTouchedAmongSeveral() {
    SkillMasterySignal regressed = new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.WEAK_SKILL, 2);
    SkillMasterySignal untouched = new SkillMasterySignal(
        AssessmentDifficulty.ADVANCED, SelectionReason.MASTERY_CONFIRMATION, 5);
    Map<String, SkillMasterySignal> base = Map.of("A", regressed, "B", untouched);

    Map<String, SkillMasterySignal> adjusted =
        HypothesisConfirmationDiagnosticSelector.adjustForRegressions(base, Set.of("A"));

    assertThat(adjusted.get("A").reason()).isEqualTo(SelectionReason.HYPOTHESIS_CONFIRMATION);
    assertThat(adjusted.get("B")).isSameAs(untouched);
  }

  @Test
  void anEmptyRegressedSetReturnsTheSameMapInstance() {
    Map<String, SkillMasterySignal> base = Map.of("A", SkillMasterySignal.noEvidence());

    Map<String, SkillMasterySignal> adjusted =
        HypothesisConfirmationDiagnosticSelector.adjustForRegressions(base, Set.of());

    assertThat(adjusted).isSameAs(base);
  }
}
