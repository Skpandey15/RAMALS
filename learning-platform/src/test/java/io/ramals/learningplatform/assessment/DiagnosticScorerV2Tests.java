package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DiagnosticScorerV2: SINGLE_CHOICE scoring proved identical to V1's rule, and FILL_BLANK
 * normalization proved to accept exactly what the plan specifies -- trim, casefold, collapse
 * internal whitespace -- and nothing more. The frozen behaviour vector in
 * {@code EngineVersionFreezeTests} pins these same rules against drift; this class is where the
 * rules are exercised case by case with a name attached to each one.
 */
class DiagnosticScorerV2Tests {

  private final DiagnosticScorerV2 scorer = new DiagnosticScorerV2();

  private static AssessmentItemScoringView mcq(List<String> correct) {
    return new AssessmentItemScoringView(
        UUID.randomUUID(), "KAFKA_BROKER", "SINGLE_CHOICE", List.of("A", "B", "C", "D"), correct,
        List.of());
  }

  private static AssessmentItemScoringView fillBlank(List<String> accepted) {
    return new AssessmentItemScoringView(
        UUID.randomUUID(), "KAFKA_TOPIC", "FILL_BLANK", List.of(), List.of(), accepted);
  }

  // -----------------------------------------------------------------------------------------
  // SINGLE_CHOICE: identical rule to V1 -- exact-set match.
  // -----------------------------------------------------------------------------------------

  @Test
  void singleChoiceMatchesExactSet() {
    AssessmentItemScoringView view = mcq(List.of("B"));
    assertThat(scorer.isCorrect(view, List.of("B"))).isTrue();
    assertThat(scorer.isCorrect(view, List.of("A"))).isFalse();
  }

  @Test
  void singleChoiceMultiSelectRequiresExactMatch() {
    AssessmentItemScoringView view = mcq(List.of("A", "B"));
    assertThat(scorer.isCorrect(view, List.of("B", "A"))).isTrue();
    assertThat(scorer.isCorrect(view, List.of("A"))).isFalse();
    assertThat(scorer.isCorrect(view, List.of("A", "B", "C"))).isFalse();
  }

  // -----------------------------------------------------------------------------------------
  // FILL_BLANK: exact match after fixed normalization. No fuzzy matching.
  // -----------------------------------------------------------------------------------------

  @Test
  void fillBlankAcceptsAnExactMatch() {
    assertThat(scorer.isCorrect(fillBlank(List.of("partition")), List.of("partition"))).isTrue();
  }

  @Test
  void fillBlankIsCaseInsensitive() {
    assertThat(scorer.isCorrect(fillBlank(List.of("partition")), List.of("Partition"))).isTrue();
    assertThat(scorer.isCorrect(fillBlank(List.of("partition")), List.of("PARTITION"))).isTrue();
  }

  @Test
  void fillBlankTrimsLeadingAndTrailingWhitespace() {
    assertThat(scorer.isCorrect(fillBlank(List.of("partition")), List.of("  partition  ")))
        .isTrue();
  }

  @Test
  void fillBlankCollapsesInternalWhitespaceRuns() {
    assertThat(scorer.isCorrect(fillBlank(List.of("commit log")), List.of("commit    log")))
        .isTrue();
  }

  @Test
  void fillBlankAcceptsAnyOneOfSeveralAlternatives() {
    AssessmentItemScoringView view = fillBlank(List.of("all", "-1"));
    assertThat(scorer.isCorrect(view, List.of("all"))).isTrue();
    assertThat(scorer.isCorrect(view, List.of("-1"))).isTrue();
    assertThat(scorer.isCorrect(view, List.of("1"))).isFalse();
  }

  @Test
  void fillBlankRejectsWrongText() {
    assertThat(scorer.isCorrect(fillBlank(List.of("partition")), List.of("segment"))).isFalse();
  }

  @Test
  void fillBlankAppliesNoFuzzyMatching() {
    // A single transposed letter, and a missing letter -- both real near-misses, both wrong, and
    // neither may be forgiven by an edit-distance tolerance the plan explicitly prohibits.
    AssessmentItemScoringView view = fillBlank(List.of("partition"));
    assertThat(scorer.isCorrect(view, List.of("partitoin"))).isFalse();
    assertThat(scorer.isCorrect(view, List.of("partiton"))).isFalse();
    assertThat(scorer.isCorrect(view, List.of("partition!"))).isFalse();
  }

  // -----------------------------------------------------------------------------------------
  // Aggregation: SINGLE_CHOICE arithmetic is byte-for-byte V1's; FILL_BLANK carries its own,
  // explicit, versioned guess-probability policy.
  // -----------------------------------------------------------------------------------------

  @Test
  void singleChoiceAggregationMatchesV1sChanceCorrection() {
    // One skill, two 4-option items, one correct: observed 0.5, guess 0.25 -> (0.5-0.25)/0.75.
    List<SkillScore> scores = scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, false)));

    assertThat(scores.getFirst().observedScore()).isEqualByComparingTo("0.5000");
    assertThat(scores.getFirst().normalizedScore()).isEqualByComparingTo("0.3333");
  }

  @Test
  void fillBlankIsTreatedAsUnguessableByChance() {
    // observed 0.5, guess 0 (the FILL_BLANK policy) -> (0.5-0)/(1-0) = 0.5: no chance correction
    // applied at all, unlike an MCQ at the same observed score.
    List<SkillScore> scores = scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_TOPIC", "FILL_BLANK", 0, true),
        new ScoredResponse("KAFKA_TOPIC", "FILL_BLANK", 0, false)));

    assertThat(scores.getFirst().observedScore()).isEqualByComparingTo("0.5000");
    assertThat(scores.getFirst().normalizedScore()).isEqualByComparingTo("0.5000");
  }

  @Test
  void mixingTypesWithinOneSkillBlendsTheirGuessProbabilities() {
    // Two MCQ items (guess 0.25 each) and two FILL_BLANK items (guess 0 each): average guess
    // probability is 0.125, not 0.25 -- the blend is genuinely per-item, not per-type.
    List<SkillScore> scores = scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "FILL_BLANK", 0, true),
        new ScoredResponse("KAFKA_BROKER", "FILL_BLANK", 0, true)));

    // observed 1.0, guess 0.125 -> (1-0.125)/(1-0.125) = 1.0000 regardless; use a mixed-correctness
    // case instead so the blended guess probability is visible in the result.
    List<SkillScore> mixed = scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, false),
        new ScoredResponse("KAFKA_BROKER", "FILL_BLANK", 0, false),
        new ScoredResponse("KAFKA_BROKER", "FILL_BLANK", 0, false)));
    // observed 0.25, guess 0.125 -> (0.25-0.125)/(1-0.125) = 0.125/0.875 = 0.1429
    assertThat(mixed.getFirst().observedScore()).isEqualByComparingTo("0.2500");
    assertThat(mixed.getFirst().normalizedScore()).isEqualByComparingTo("0.1429");
    assertThat(scores.getFirst().normalizedScore()).isEqualByComparingTo("1.0000");
  }

  @Test
  void shortAnswerAndUseCaseHaveNoDeterministicScorer() {
    AssessmentItemScoringView shortAnswer = new AssessmentItemScoringView(
        UUID.randomUUID(), "KAFKA_TOPIC", "SHORT_ANSWER", List.of(), List.of(), List.of());
    assertThatThrownBy(() -> scorer.isCorrect(shortAnswer, List.of("anything")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no deterministic scorer");

    AssessmentItemScoringView useCase = new AssessmentItemScoringView(
        UUID.randomUUID(), "KAFKA_TOPIC", "USE_CASE", List.of(), List.of(), List.of());
    assertThatThrownBy(() -> scorer.isCorrect(useCase, List.of("anything")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no deterministic scorer");
  }

  @Test
  void anUnknownItemTypeFailsClosed() {
    AssessmentItemScoringView view = new AssessmentItemScoringView(
        UUID.randomUUID(), "KAFKA_TOPIC", "ESSAY", List.of(), List.of(), List.of());
    assertThatThrownBy(() -> scorer.isCorrect(view, List.of("anything")))
        .isInstanceOf(io.ramals.learningplatform.curriculum.UnknownAssessmentItemTypeException.class);
  }
}
