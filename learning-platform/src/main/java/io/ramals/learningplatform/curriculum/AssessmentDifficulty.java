package io.ramals.learningplatform.curriculum;

import java.util.List;
import java.util.Locale;

/**
 * The difficulty vocabulary assessment items are authored in
 * ({@code assessment_item_version.difficulty}), and the single place where it is related to the
 * {@link MasteryDifficultyBand} vocabulary a skill states its requirement in.
 *
 * <p>The two vocabularies have coexisted since V003/V005 with no mapping between them, which is why
 * the difficulty half of the mastery gate could never be satisfied: a skill required EASY and
 * MEDIUM, and the only thing the platform could observe was that an item was FOUNDATIONAL. The
 * relationship is declared here, on the enum constant, so it cannot be restated differently
 * somewhere else -- there is no string comparison anywhere else to disagree with.
 *
 * <p>The mapping is ordinal and total: three authored levels onto three required bands, in order.
 * It matches the seeded curriculum, where FOUNDATIONAL skills carry {@code ARRAY['EASY','MEDIUM']}
 * and ADVANCED skills carry {@code ARRAY['MEDIUM','HARD']} -- the same progression, expressed twice.
 */
public enum AssessmentDifficulty {

  FOUNDATIONAL(MasteryDifficultyBand.EASY),
  INTERMEDIATE(MasteryDifficultyBand.MEDIUM),
  ADVANCED(MasteryDifficultyBand.HARD);

  private final MasteryDifficultyBand band;

  AssessmentDifficulty(MasteryDifficultyBand band) {
    this.band = band;
  }

  /** The mastery band an item of this difficulty provides evidence at. */
  public MasteryDifficultyBand band() {
    return band;
  }

  /**
   * Parses a stored item difficulty, failing closed on anything unrecognized.
   *
   * @throws UnknownDifficultyException if {@code value} is null, blank, or not a known difficulty
   */
  public static AssessmentDifficulty of(String value) {
    if (value == null || value.isBlank()) {
      throw new UnknownDifficultyException("An assessment item difficulty is required.");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (AssessmentDifficulty difficulty : values()) {
      if (difficulty.name().equals(normalized)) {
        return difficulty;
      }
    }
    throw new UnknownDifficultyException(
        "Unknown assessment item difficulty: " + value
            + ". Known difficulties: " + List.of(values()) + ".");
  }

  /** The band an item of the given stored difficulty provides evidence at. Fails closed. */
  public static MasteryDifficultyBand bandOf(String itemDifficulty) {
    return of(itemDifficulty).band();
  }
}
