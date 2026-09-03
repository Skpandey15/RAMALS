package io.ramals.learningplatform.curriculum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one place the two difficulty vocabularies are related.
 *
 * <p>Before this mapping existed, an item's FOUNDATIONAL and a skill's required EASY were compared
 * as strings, never matched, and made the mastery band gate permanently unsatisfiable. These tests
 * pin the mapping itself and, just as importantly, pin that anything outside it is refused rather
 * than quietly dropped.
 */
class DifficultyBandMappingTests {

  @Test
  void foundationalMapsToEasy() {
    assertThat(AssessmentDifficulty.FOUNDATIONAL.band()).isEqualTo(MasteryDifficultyBand.EASY);
    assertThat(AssessmentDifficulty.bandOf("FOUNDATIONAL")).isEqualTo(MasteryDifficultyBand.EASY);
  }

  @Test
  void intermediateMapsToMedium() {
    assertThat(AssessmentDifficulty.INTERMEDIATE.band()).isEqualTo(MasteryDifficultyBand.MEDIUM);
    assertThat(AssessmentDifficulty.bandOf("INTERMEDIATE")).isEqualTo(MasteryDifficultyBand.MEDIUM);
  }

  @Test
  void advancedMapsToHard() {
    assertThat(AssessmentDifficulty.ADVANCED.band()).isEqualTo(MasteryDifficultyBand.HARD);
    assertThat(AssessmentDifficulty.bandOf("ADVANCED")).isEqualTo(MasteryDifficultyBand.HARD);
  }

  @Test
  void everyAuthoredDifficultyHasABand() {
    // Total by construction: a fourth difficulty cannot be added without choosing its band, because
    // the enum constant will not compile without one.
    assertThat(List.of(AssessmentDifficulty.values()))
        .allSatisfy(difficulty -> assertThat(difficulty.band()).isNotNull());
    assertThat(List.of(AssessmentDifficulty.values())
        .stream().map(AssessmentDifficulty::band).distinct().toList())
        .containsExactlyInAnyOrder(MasteryDifficultyBand.values());
  }

  @Test
  void anUnknownDifficultyFailsClosed() {
    // The two wrong answers here would be defaulting to EASY, which credits coverage the learner
    // never demonstrated, and defaulting to HARD, which withholds coverage they did.
    assertThatThrownBy(() -> AssessmentDifficulty.bandOf("TRIVIAL"))
        .isInstanceOf(UnknownDifficultyException.class)
        .hasMessageContaining("TRIVIAL");
    assertThatThrownBy(() -> AssessmentDifficulty.of("EASY"))
        .as("a mastery band is not an item difficulty, and must not be accepted as one")
        .isInstanceOf(UnknownDifficultyException.class);
    assertThatThrownBy(() -> AssessmentDifficulty.of(null))
        .isInstanceOf(UnknownDifficultyException.class);
    assertThatThrownBy(() -> AssessmentDifficulty.of("  "))
        .isInstanceOf(UnknownDifficultyException.class);
  }

  @Test
  void anUnknownBandFailsClosed() {
    assertThatThrownBy(() -> MasteryDifficultyBand.of("EXTREME"))
        .isInstanceOf(UnknownDifficultyException.class)
        .hasMessageContaining("EXTREME");
    assertThatThrownBy(() -> MasteryDifficultyBand.of("FOUNDATIONAL"))
        .as("an item difficulty is not a band, and must not be accepted as one")
        .isInstanceOf(UnknownDifficultyException.class);
    assertThatThrownBy(() -> MasteryDifficultyBand.setOf(List.of("EASY", "SPICY")))
        .isInstanceOf(UnknownDifficultyException.class);
  }

  @Test
  void storedValuesParseRegardlessOfCaseAndPadding() {
    assertThat(AssessmentDifficulty.bandOf(" foundational ")).isEqualTo(MasteryDifficultyBand.EASY);
    assertThat(MasteryDifficultyBand.setOf(List.of("EASY", "medium")))
        .containsExactlyInAnyOrder(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM);
    assertThat(MasteryDifficultyBand.setOf(null)).isEmpty();
    assertThat(MasteryDifficultyBand.setOf(List.of())).isEmpty();
  }
}
