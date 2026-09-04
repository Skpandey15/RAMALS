package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link PrerequisiteAwareDiagnosticSelector#adjustForPrerequisites}: the one new rule V3 adds on
 * top of V2's unmodified signal map. Every case asserts the ADR's own boundary -- capped and
 * deprioritised, never removed from the map entirely (removal would be exclusion, which
 * M2-ADR-023 §1 forbids).
 */
class PrerequisiteAwareDiagnosticSelectorTests {

  @Test
  void aSignalAlreadyAtFoundationalIsNeverTouched() {
    SkillMasterySignal unseen = SkillMasterySignal.noEvidence();
    Map<String, SkillMasterySignal> base = Map.of("B", unseen);
    // B's only prerequisite is unsecured -- but there is nothing to cap, since FOUNDATIONAL is
    // already the floor, and relabelling the reason would misattribute a hold that was never about
    // prerequisites in the first place.
    Map<String, List<String>> prerequisites = Map.of("B", List.of("A"));
    Map<String, MasteryStatus> statuses = Map.of("A", MasteryStatus.DEVELOPING);

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, prerequisites, statuses);

    assertThat(adjusted.get("B")).isSameAs(unseen);
  }

  @Test
  void anEscalatedSignalWithNoPrerequisitesIsUntouched() {
    SkillMasterySignal progression = new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> base = Map.of("A", progression);

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, Map.of(), Map.of());

    assertThat(adjusted.get("A")).isSameAs(progression);
  }

  @Test
  void anEscalatedSignalWithEverySecuredPrerequisiteIsUntouched() {
    SkillMasterySignal progression = new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> base = Map.of("B", progression);
    Map<String, List<String>> prerequisites = Map.of("B", List.of("A"));
    Map<String, MasteryStatus> statuses = Map.of("A", MasteryStatus.MASTERED);

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, prerequisites, statuses);

    assertThat(adjusted.get("B")).isSameAs(progression);
  }

  @Test
  void anEscalatedSignalWithAnUnsecuredPrerequisiteIsCappedAndDeprioritisedNotRemoved() {
    SkillMasterySignal progression = new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> base = Map.of("B", progression);
    Map<String, List<String>> prerequisites = Map.of("B", List.of("A"));
    Map<String, MasteryStatus> statuses = Map.of("A", MasteryStatus.DEVELOPING);

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, prerequisites, statuses);

    // Still present in the map -- selected, not excluded -- per M2-ADR-023 §1.
    assertThat(adjusted).containsKey("B");
    SkillMasterySignal result = adjusted.get("B");
    assertThat(result.targetDifficulty()).isEqualTo(AssessmentDifficulty.FOUNDATIONAL);
    assertThat(result.reason()).isEqualTo(SelectionReason.PREREQUISITE_NOT_SECURED);
    assertThat(result.priority()).isEqualTo(5); // demoted to MASTERY_CONFIRMATION's tier
  }

  @Test
  void anUnknownPrerequisiteWithNoRecordedStatusIsTreatedAsNotSecured() {
    SkillMasterySignal progression = new SkillMasterySignal(
        AssessmentDifficulty.ADVANCED, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> base = Map.of("B", progression);
    Map<String, List<String>> prerequisites = Map.of("B", List.of("A"));
    // "A" has no entry in statuses at all -- unknown, not confirmed secure.

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, prerequisites, Map.of());

    assertThat(adjusted.get("B").reason()).isEqualTo(SelectionReason.PREREQUISITE_NOT_SECURED);
  }

  @Test
  void multiplePrerequisitesRequireEveryOneSecuredNotJustOne() {
    SkillMasterySignal progression = new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> base = Map.of("C", progression);
    Map<String, List<String>> prerequisites = Map.of("C", List.of("A", "B"));
    // A is secured, B is not -- one unsecured prerequisite is enough to hold the escalation back.
    Map<String, MasteryStatus> statuses =
        Map.of("A", MasteryStatus.MASTERED, "B", MasteryStatus.NEEDS_PRACTICE);

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, prerequisites, statuses);

    assertThat(adjusted.get("C").reason()).isEqualTo(SelectionReason.PREREQUISITE_NOT_SECURED);
  }

  @Test
  void skillsAbsentFromEitherMapAreUnaffected() {
    SkillMasterySignal unseen = SkillMasterySignal.noEvidence();
    SkillMasterySignal progression = new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> base = Map.of("A", unseen, "B", progression);
    // Neither A nor B appear in the prerequisite map at all.

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, Map.of(), Map.of());

    assertThat(adjusted.get("A")).isSameAs(unseen);
    assertThat(adjusted.get("B")).isSameAs(progression);
  }

  @Test
  void everySkillInTheInputMapHasAResultInTheOutputMap() {
    Map<String, SkillMasterySignal> base = Map.of(
        "A", SkillMasterySignal.noEvidence(),
        "B", new SkillMasterySignal(AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4),
        "C", new SkillMasterySignal(AssessmentDifficulty.ADVANCED, SelectionReason.MASTERY_CONFIRMATION, 5));

    Map<String, SkillMasterySignal> adjusted =
        PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(base, Map.of(), Map.of());

    assertThat(adjusted.keySet()).containsExactlyInAnyOrder("A", "B", "C");
  }
}
