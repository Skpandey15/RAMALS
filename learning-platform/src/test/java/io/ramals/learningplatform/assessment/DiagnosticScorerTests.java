package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticScorerTests {

  private final DiagnosticScorer scorer = new DiagnosticScorer();

  @Test
  void correctnessIsExactOptionSetMatch() {
    assertThat(scorer.isCorrect(List.of("B"), List.of("B"))).isTrue();
    assertThat(scorer.isCorrect(List.of("A"), List.of("B"))).isFalse();
    assertThat(scorer.isCorrect(List.of(), List.of("B"))).isFalse();
  }

  @Test
  void guessingAwareNormalizationCorrectsChancePerformance() {
    // One skill, two 4-option items, one correct: observed 0.5, guess 0.25 -> (0.5-0.25)/0.75.
    List<SkillScore> scores = scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, false)));

    assertThat(scores).hasSize(1);
    SkillScore broker = scores.getFirst();
    assertThat(broker.itemsAnswered()).isEqualTo(2);
    assertThat(broker.itemsCorrect()).isEqualTo(1);
    assertThat(broker.observedScore()).isEqualByComparingTo("0.5000");
    assertThat(broker.normalizedScore()).isEqualByComparingTo("0.3333");
  }

  @Test
  void fullyCorrectNormalizesToOneAndChanceFloorsAtZero() {
    List<SkillScore> perfect = scorer.aggregate(List.of(new ScoredResponse("S", "SINGLE_CHOICE", 4, true)));
    assertThat(perfect.getFirst().normalizedScore()).isEqualByComparingTo("1.0000");

    List<SkillScore> wrong = scorer.aggregate(List.of(new ScoredResponse("S", "SINGLE_CHOICE", 4, false)));
    assertThat(wrong.getFirst().normalizedScore()).isEqualByComparingTo("0.0000");
  }

  @Test
  void scoresAreGroupedPerSkillAndOrderedDeterministically() {
    List<SkillScore> scores = scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_TOPIC", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true)));

    assertThat(scores).extracting(SkillScore::skillCode)
        .containsExactly("KAFKA_BROKER", "KAFKA_TOPIC");
  }
}
