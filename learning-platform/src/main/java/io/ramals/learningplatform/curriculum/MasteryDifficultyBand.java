package io.ramals.learningplatform.curriculum;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The difficulty vocabulary a skill states its mastery requirement in
 * ({@code skill_version.required_difficulty_bands}).
 *
 * <p>Deliberately separate from {@link AssessmentDifficulty}, which is the vocabulary an individual
 * assessment item is authored in. They are two different axes -- how hard a question is, versus how
 * hard a learner must demonstrate before a skill counts as mastered -- and the platform has always
 * had both. What it did not have was a stated relationship between them; see
 * {@link AssessmentDifficulty#band()}.
 */
public enum MasteryDifficultyBand {
  EASY,
  MEDIUM,
  HARD;

  /**
   * Parses a stored band, failing closed on anything unrecognized.
   *
   * @throws UnknownDifficultyException if {@code value} is null, blank, or not a known band
   */
  public static MasteryDifficultyBand of(String value) {
    if (value == null || value.isBlank()) {
      throw new UnknownDifficultyException("A mastery difficulty band is required.");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (MasteryDifficultyBand band : values()) {
      if (band.name().equals(normalized)) {
        return band;
      }
    }
    throw new UnknownDifficultyException(
        "Unknown mastery difficulty band: " + value + ". Known bands: " + List.of(values()) + ".");
  }

  /** Parses a stored set of bands, failing closed if any member is unrecognized. */
  public static Set<MasteryDifficultyBand> setOf(List<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return values.stream().map(MasteryDifficultyBand::of).collect(Collectors.toUnmodifiableSet());
  }
}
