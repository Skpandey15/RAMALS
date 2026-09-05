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
 * reprioritise, never re-band. This is H4a (cross-attempt regression confirmation) only -- H4b's
 * hypothesis-driven related/root-cause probe selection is
 * {@link HypothesisDrivenProbeDiagnosticSelector} (DIAGNOSTIC_SELECTION_V5), a separate class, tested
 * separately.
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
    // regression from anything -- excluded deliberately, not because it happens to rank lowest.
    assertThat(HypothesisConfirmationDiagnosticSelector
        .isRegression(MasteryStatus.INSUFFICIENT_EVIDENCE, MasteryStatus.NEEDS_RETEACH)).isFalse();
  }

  @Test
  void insufficientEvidenceAsTheLatestStatusIsNeverARegressionEither() {
    // Mastery only accumulates evidence forward, so a real snapshot regressing all the way back to
    // "no evidence yet" should not happen in practice. Defended anyway: a status absent from V4's
    // rank map is simply not comparable on either side, never a false positive.
    assertThat(HypothesisConfirmationDiagnosticSelector
        .isRegression(MasteryStatus.MASTERED, MasteryStatus.INSUFFICIENT_EVIDENCE)).isFalse();
  }

  @Test
  void nullPreviousOrLatestIsNeverARegression() {
    assertThat(HypothesisConfirmationDiagnosticSelector.isRegression(null, MasteryStatus.DEVELOPING))
        .isFalse();
    assertThat(HypothesisConfirmationDiagnosticSelector.isRegression(MasteryStatus.MASTERED, null))
        .isFalse();
  }

  /**
   * Every ordered pair among the four statuses V4's frozen rank actually orders (NEEDS_RETEACH <
   * NEEDS_PRACTICE < DEVELOPING < MASTERED), spelled out explicitly as a truth table rather than
   * derived from the same rank map the class under test uses -- so this is a check against the
   * documented contract, not a restatement of the implementation. If a future edit to
   * {@code MASTERY_RANK} silently changed what V4 considers a regression, this is what would catch
   * it: the enum's own {@code ordinal()} happens to agree with this table today, but nothing here
   * relies on that, and reordering the enum's declaration must not move a single one of these 16
   * results.
   */
  @Test
  void everyRankedStatusPairMatchesTheFrozenRegressionContract() {
    assertRegression(MasteryStatus.NEEDS_RETEACH, MasteryStatus.NEEDS_RETEACH, false);
    assertRegression(MasteryStatus.NEEDS_RETEACH, MasteryStatus.NEEDS_PRACTICE, false);
    assertRegression(MasteryStatus.NEEDS_RETEACH, MasteryStatus.DEVELOPING, false);
    assertRegression(MasteryStatus.NEEDS_RETEACH, MasteryStatus.MASTERED, false);

    assertRegression(MasteryStatus.NEEDS_PRACTICE, MasteryStatus.NEEDS_RETEACH, true);
    assertRegression(MasteryStatus.NEEDS_PRACTICE, MasteryStatus.NEEDS_PRACTICE, false);
    assertRegression(MasteryStatus.NEEDS_PRACTICE, MasteryStatus.DEVELOPING, false);
    assertRegression(MasteryStatus.NEEDS_PRACTICE, MasteryStatus.MASTERED, false);

    assertRegression(MasteryStatus.DEVELOPING, MasteryStatus.NEEDS_RETEACH, true);
    assertRegression(MasteryStatus.DEVELOPING, MasteryStatus.NEEDS_PRACTICE, true);
    assertRegression(MasteryStatus.DEVELOPING, MasteryStatus.DEVELOPING, false);
    assertRegression(MasteryStatus.DEVELOPING, MasteryStatus.MASTERED, false);

    assertRegression(MasteryStatus.MASTERED, MasteryStatus.NEEDS_RETEACH, true);
    assertRegression(MasteryStatus.MASTERED, MasteryStatus.NEEDS_PRACTICE, true);
    assertRegression(MasteryStatus.MASTERED, MasteryStatus.DEVELOPING, true);
    assertRegression(MasteryStatus.MASTERED, MasteryStatus.MASTERED, false);
  }

  private static void assertRegression(MasteryStatus previous, MasteryStatus latest, boolean expected) {
    assertThat(HypothesisConfirmationDiagnosticSelector.isRegression(previous, latest))
        .as("%s -> %s", previous, latest)
        .isEqualTo(expected);
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
