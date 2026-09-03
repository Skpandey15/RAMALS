package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticScorer;
import io.ramals.learningplatform.assessment.ScoredResponse;
import io.ramals.learningplatform.assessment.SkillScore;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a diagnostic is allowed to conclude.
 *
 * <p>A diagnostic establishes a starting hypothesis, not a verdict. These tests run the real
 * scoring, mastery, and confidence engines over the seeded KAFKA v1 configuration and assert the
 * rule that follows from that: five correct answers, one item per skill, produce a top mastery
 * score and still leave every skill at INSUFFICIENT_EVIDENCE, because one item per skill is not
 * enough evidence to trust the score.
 *
 * <p>The individual engines have their own unit tests. What is pinned here is the property that
 * emerges from combining them -- the thing that would silently break if someone "fixed" mastery by
 * lowering a volume gate or collapsing score and confidence into one number.
 */
class DiagnosticEvidenceBaselineTests {

  // The seeded skill_version configuration for the five skills the KAFKA v1 diagnostic covers.
  private static final BigDecimal MASTERY_THRESHOLD = new BigDecimal("0.8000");
  private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.7500");
  private static final int REQUIRED_EVIDENCE_COUNT = 5;
  private static final int REQUIRED_OBJECTIVES = 1;
  private static final int OPTIONS_PER_ITEM = 4;

  private static final List<String> DIAGNOSTIC_SKILLS = List.of(
      "KAFKA_BROKER", "KAFKA_CONSUMER_GROUPS", "KAFKA_PARTITION",
      "KAFKA_PRODUCER_ACKS", "KAFKA_TOPIC");

  private final DiagnosticScorer scorer = new DiagnosticScorer();
  private final WeightedMasteryCalculator mastery = new WeightedMasteryCalculator();
  private final EvidenceConfidenceCalculator confidence = new EvidenceConfidenceCalculator();
  private final MasteryStatusPolicy statusPolicy = new MasteryStatusPolicy();

  @Test
  void fiveOutOfFiveLeavesEverySkillAtInsufficientEvidence() {
    List<SkillScore> scores = scoreOnePerSkill(DIAGNOSTIC_SKILLS, skill -> true);

    assertThat(scores).hasSize(5);
    for (SkillScore score : scores) {
      List<Evidence> evidence = List.of(diagnosticEvidence(score));
      MasteryOutcome outcome =
          mastery.compute(evidence, MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT);

      // A perfect answer on a four-option item is worth a full chance-corrected score...
      assertThat(outcome.masteryScore()).isEqualByComparingTo("1.0000");
      // ...and one item is still one item. Volume, not correctness, is what is missing.
      assertThat(outcome.status())
          .as("skill %s after a single correct item", score.skillCode())
          .isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
      assertThat(outcome.itemsConsidered()).isEqualTo(1);
    }
  }

  @Test
  void confidenceStaysBelowItsThresholdAfterASingleItemPerSkill() {
    SkillScore score = scoreOnePerSkill(List.of("KAFKA_BROKER"), skill -> true).getFirst();
    ConfidenceOutcome outcome = confidence.compute(confidenceInputs(List.of(score)));

    // 0.40*(1/5) + 0.35*0 + 0.15*1 + 0.10*1. Volume is the fifth of the blend that one item can
    // earn; objective coverage is zero because MVP-0 evidence is not objective-tagged.
    assertThat(outcome.volumeSufficiency()).isEqualByComparingTo("0.2000");
    assertThat(outcome.confidence()).isEqualByComparingTo("0.3300");
    assertThat(outcome.confidence()).isLessThan(CONFIDENCE_THRESHOLD);
  }

  @Test
  void masteryScoreAndEvidenceConfidenceAreIndependentQuantities() {
    SkillScore perfect = scoreOnePerSkill(List.of("KAFKA_BROKER"), skill -> true).getFirst();

    MasteryOutcome outcome = mastery.compute(
        List.of(diagnosticEvidence(perfect)), MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT);
    ConfidenceOutcome sufficiency = confidence.compute(confidenceInputs(List.of(perfect)));

    // The score is as high as it goes and the confidence in it is as low as a single item deserves.
    // A design that reported one number could not say both of these things at once.
    assertThat(outcome.masteryScore()).isEqualByComparingTo("1.0000");
    assertThat(sufficiency.confidence()).isLessThan(outcome.masteryScore());
    assertThat(outcome.status()).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void anIncorrectAnswerStillWritesEvidenceForItsOwnSkill() {
    List<SkillScore> scores =
        scoreOnePerSkill(DIAGNOSTIC_SKILLS, skill -> !skill.equals("KAFKA_PARTITION"));

    // Every skill is measured, including the one that was answered wrongly: a wrong answer is
    // evidence about that skill, not an absence of evidence.
    assertThat(scores).extracting(SkillScore::skillCode)
        .containsExactlyElementsOf(DIAGNOSTIC_SKILLS.stream().sorted().toList());

    SkillScore missed = scores.stream()
        .filter(score -> score.skillCode().equals("KAFKA_PARTITION"))
        .findFirst()
        .orElseThrow();
    assertThat(missed.itemsCorrect()).isZero();
    assertThat(missed.itemsAnswered()).isEqualTo(1);
    // Floored at zero rather than driven negative: the score model is [0,1], and a wrong answer
    // acts on mastery by pulling the weighted average down, not by subtracting from it.
    assertThat(missed.normalizedScore()).isEqualByComparingTo("0.0000");

    MasteryOutcome outcome = mastery.compute(
        List.of(diagnosticEvidence(missed)), MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT);
    assertThat(outcome.masteryScore()).isEqualByComparingTo("0.0000");
    assertThat(outcome.status()).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void repeatedDiagnosticsAccumulateEvidenceRatherThanReplacingIt() {
    SkillScore perAttempt = scoreOnePerSkill(List.of("KAFKA_BROKER"), skill -> true).getFirst();

    List<Evidence> ledger = new ArrayList<>();
    List<MasteryStatus> statusAfterEachAttempt = new ArrayList<>();
    for (int attempt = 1; attempt <= REQUIRED_EVIDENCE_COUNT; attempt++) {
      // Each re-run is a new attempt and therefore a new lineage key, so it appends a row rather
      // than superseding the last one. The engine sees a growing ledger, not a moving value.
      ledger.add(diagnosticEvidence(perAttempt));
      statusAfterEachAttempt.add(
          mastery.compute(ledger, MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT).status());
    }

    assertThat(statusAfterEachAttempt.subList(0, 4))
        .containsOnly(MasteryStatus.INSUFFICIENT_EVIDENCE);
    // Only once the volume gate is satisfied does the score get to speak.
    assertThat(statusAfterEachAttempt.getLast()).isEqualTo(MasteryStatus.MASTERED);
  }

  @Test
  void theStatusPolicyHoldsMasteryBackUntilTheEvidenceIsTrusted() {
    // The provisional MASTERED above is the mastery model's verdict on the score alone. The status
    // policy is the second gate, and a diagnostic-only ledger does not clear it.
    ConfidenceOutcome afterFiveDiagnostics = confidence.compute(new ConfidenceInputs(
        REQUIRED_EVIDENCE_COUNT, REQUIRED_EVIDENCE_COUNT, 0, REQUIRED_OBJECTIVES, 0,
        List.of(new BigDecimal("1.0000"), new BigDecimal("1.0000"), new BigDecimal("1.0000"),
            new BigDecimal("1.0000"), new BigDecimal("1.0000"))));

    MasteryStatus refined = statusPolicy.refine(
        MasteryStatus.MASTERED, afterFiveDiagnostics.confidence(), CONFIDENCE_THRESHOLD,
        java.util.Set.of("EASY", "MEDIUM"), java.util.Set.of());

    assertThat(refined).isEqualTo(MasteryStatus.DEVELOPING);
  }

  /** Scores one four-option item per skill, correct unless the predicate says otherwise. */
  private List<SkillScore> scoreOnePerSkill(
      List<String> skills, java.util.function.Predicate<String> answeredCorrectly) {
    List<ScoredResponse> responses = skills.stream()
        .map(skill ->
            new ScoredResponse(skill, "SINGLE_CHOICE", OPTIONS_PER_ITEM, answeredCorrectly.test(skill)))
        .toList();
    return scorer.aggregate(responses);
  }

  private static ConfidenceInputs confidenceInputs(List<SkillScore> scores) {
    return new ConfidenceInputs(
        scores.stream().mapToInt(SkillScore::itemsAnswered).sum(),
        REQUIRED_EVIDENCE_COUNT,
        // MVP-0 evidence carries no objective tags, so no required objective is credited.
        0,
        REQUIRED_OBJECTIVES,
        0,
        scores.stream().map(SkillScore::normalizedScore).toList());
  }

  private static Evidence diagnosticEvidence(SkillScore score) {
    Instant now = Instant.parse("2026-09-03T00:00:00Z");
    return new Evidence(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DIAGNOSTIC", "ASSESSMENT",
        UUID.randomUUID(), UUID.randomUUID(), DiagnosticScorer.SCORING_VERSION, null,
        "ASSESSMENT_ATTEMPT:test:SKILL:" + score.skillCode(),
        score.observedScore(), score.normalizedScore(),
        score.itemsAnswered(), score.itemsCorrect(), EvidenceCoverage.none(),
        "interaction", now, now);
  }
}
