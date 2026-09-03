package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticScorer;
import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * One learner, one skill, from a cold start to MASTERED under V2.
 *
 * <p>This is the end-to-end claim the V2 work exists to make good on: a diagnostic sets a starting
 * hypothesis and cannot confer mastery, and subsequent learning interactions raise confidence and
 * coverage until every gate is genuinely satisfied. It runs the real engines over an evidence
 * ledger built the way the production path builds one -- coverage computed from item difficulty
 * through {@link AssessmentDifficulty}, objectives carried as identifiers -- so nothing here can
 * pass by asserting a value the test itself invented.
 *
 * <p>The aggregation this file performs by hand is deliberately the same aggregation
 * {@code MasteryService} performs: intersect covered objectives with required ones, union the
 * covered bands, and feed both to the V2 engines. The service's own wiring is exercised against a
 * real database by {@code MasteryEnginePersistenceIntegrationTests}.
 */
class MasteryJourneyV2Tests {

  // A skill configured like the seeded KAFKA_BROKER: two required objectives, five items of
  // evidence, a 0.80 mastery threshold, 0.75 confidence, and EASY plus MEDIUM required.
  private static final BigDecimal MASTERY_THRESHOLD = new BigDecimal("0.8000");
  private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.7500");
  private static final int REQUIRED_EVIDENCE_COUNT = 5;
  private static final Set<MasteryDifficultyBand> REQUIRED_BANDS =
      Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM);

  private static final UUID OBJECTIVE_A = UUID.fromString("01900000-0000-7000-8000-000000000301");
  private static final UUID OBJECTIVE_B = UUID.fromString("01900000-0000-7000-8000-0000000003a1");
  private static final Set<UUID> REQUIRED_OBJECTIVES = Set.of(OBJECTIVE_A, OBJECTIVE_B);
  private static final UUID SOMEONE_ELSES_OBJECTIVE =
      UUID.fromString("01900000-0000-7000-8000-0000000003ff");

  private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

  private final WeightedMasteryCalculator mastery = new WeightedMasteryCalculator();
  private final EvidenceConfidenceCalculatorV2 confidence = new EvidenceConfidenceCalculatorV2();
  private final MasteryStatusPolicyV2 statusPolicy = new MasteryStatusPolicyV2();

  @Test
  void aLearnerReachesMasteredOnlyAfterBreadthAsWellAsDepth() {
    List<Evidence> ledger = new ArrayList<>();
    List<MasteryStatus> timeline = new ArrayList<>();

    // 1. The diagnostic. One FOUNDATIONAL item, answered correctly, against one objective.
    ledger.add(observation("DIAGNOSTIC", "1.0000", 1, 1,
        List.of(OBJECTIVE_A), AssessmentDifficulty.FOUNDATIONAL));
    timeline.add(statusOf(ledger));

    // 2. A practice set on the same objective, still all FOUNDATIONAL. Depth without breadth.
    ledger.add(observation("PRACTICE", "1.0000", 3, 3,
        List.of(OBJECTIVE_A), AssessmentDifficulty.FOUNDATIONAL));
    timeline.add(statusOf(ledger));

    // 3. A quiz reaching the second objective, still only at FOUNDATIONAL. Volume is satisfied and
    //    objective coverage is complete -- but every observation so far maps to EASY.
    ledger.add(observation("QUIZ", "1.0000", 2, 2,
        List.of(OBJECTIVE_B), AssessmentDifficulty.FOUNDATIONAL));
    timeline.add(statusOf(ledger));

    // 4. An INTERMEDIATE scenario: the first evidence at MEDIUM, and the last gate to fall.
    ledger.add(observation("SCENARIO", "1.0000", 2, 2,
        List.of(OBJECTIVE_B), AssessmentDifficulty.INTERMEDIATE));
    timeline.add(statusOf(ledger));

    assertThat(timeline).containsExactly(
        MasteryStatus.INSUFFICIENT_EVIDENCE,  // one item; the volume gate has not been met
        MasteryStatus.INSUFFICIENT_EVIDENCE,  // four items; still short of the required five
        MasteryStatus.DEVELOPING,             // volume and objectives met, but only EASY observed
        MasteryStatus.MASTERED);              // MEDIUM observed: every gate genuinely satisfied

    // And the step that changed the verdict changed nothing about the score. Mastery was already
    // 1.0000 at step 3; what step 4 added was breadth of measurement.
    assertThat(mastery.compute(ledger, MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT).masteryScore())
        .isEqualByComparingTo("1.0000");
    assertThat(coveredBands(ledger))
        .containsExactlyInAnyOrder(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM);
  }

  @Test
  void aFiveOutOfFiveDiagnosticAloneStillDoesNotConferMastery() {
    // The rule that started this work, now checked against V2 rather than against V1's inability
    // to produce anything else. Five diagnostics on one objective at one band: perfect score, and
    // the breadth requirements are still unmet.
    List<Evidence> ledger = new ArrayList<>();
    for (int attempt = 0; attempt < 5; attempt++) {
      ledger.add(observation("DIAGNOSTIC", "1.0000", 1, 1,
          List.of(OBJECTIVE_A), AssessmentDifficulty.FOUNDATIONAL));
    }

    assertThat(mastery.compute(ledger, MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT).status())
        .as("the score model alone would confirm this")
        .isEqualTo(MasteryStatus.MASTERED);
    assertThat(statusOf(ledger))
        .as("the policy will not, on one objective at one band")
        .isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void legacyEvidenceWithoutCoverageMetadataGrantsNoCoverage() {
    // Rows written before V046 carry no coverage. They still count towards volume and towards the
    // mastery score -- they were real observations -- but they cannot be read as having covered
    // anything, so a ledger made only of them cannot reach MASTERED.
    List<Evidence> legacy = new ArrayList<>();
    for (int attempt = 0; attempt < 6; attempt++) {
      legacy.add(new Evidence(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DIAGNOSTIC", "ASSESSMENT",
          UUID.randomUUID(), UUID.randomUUID(), DiagnosticScorer.SCORING_VERSION, null,
          "legacy:" + attempt, new BigDecimal("1.0000"), new BigDecimal("1.0000"), 1, 1,
          EvidenceCoverage.none(), "interaction", NOW, NOW));
    }

    assertThat(mastery.compute(legacy, MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT).status())
        .isEqualTo(MasteryStatus.MASTERED);
    assertThat(coveredObjectives(legacy)).isEmpty();
    assertThat(coveredBands(legacy)).isEmpty();
    assertThat(statusOf(legacy)).isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void evidenceAgainstAnotherSkillsObjectiveCannotInflateCoverage() {
    // Coverage is intersected with the objectives this skill requires, so an observation tagged
    // against an objective belonging elsewhere credits nothing here. The database refuses to
    // create such a tag in the first place (V046's skill-match trigger); this is the second layer.
    List<Evidence> ledger = new ArrayList<>();
    ledger.add(observation("DIAGNOSTIC", "1.0000", 3, 3,
        List.of(OBJECTIVE_A), AssessmentDifficulty.FOUNDATIONAL));
    ledger.add(observation("QUIZ", "1.0000", 3, 3,
        List.of(SOMEONE_ELSES_OBJECTIVE), AssessmentDifficulty.INTERMEDIATE));

    assertThat(coveredObjectives(ledger)).containsExactly(OBJECTIVE_A);
    assertThat(confidenceOf(ledger).objectiveCoverage())
        .as("one of two required objectives, not two of two")
        .isEqualByComparingTo("0.5000");

    // The counterfactual is what makes the point: the identical ledger, with that second
    // observation tagged against the objective this skill actually requires, reaches full
    // coverage. The difference is the objective identifier and nothing else.
    List<Evidence> properlyTagged = List.of(ledger.getFirst(),
        observation("QUIZ", "1.0000", 3, 3,
            List.of(OBJECTIVE_B), AssessmentDifficulty.INTERMEDIATE));
    assertThat(coveredObjectives(properlyTagged))
        .containsExactlyInAnyOrder(OBJECTIVE_A, OBJECTIVE_B);
    assertThat(confidenceOf(properlyTagged).objectiveCoverage()).isEqualByComparingTo("1.0000");
    assertThat(confidenceOf(ledger).confidence())
        .isLessThan(confidenceOf(properlyTagged).confidence());
  }

  @Test
  void adjustmentEvidenceRestatesAScoreWithoutGrantingCoverage() {
    // ADJUSTMENT is bookkeeping, not observation. It must not weigh into mastery and must not
    // grant breadth, or a correction could be used to manufacture coverage.
    List<Evidence> ledger = new ArrayList<>();
    ledger.add(observation("DIAGNOSTIC", "1.0000", 5, 5,
        List.of(OBJECTIVE_A, OBJECTIVE_B), AssessmentDifficulty.FOUNDATIONAL));
    Evidence adjustment = new Evidence(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ADJUSTMENT", "ADJUSTMENT",
        null, null, null, ledger.getFirst().id(), "adjustment:1",
        new BigDecimal("1.0000"), new BigDecimal("1.0000"), 0, 0,
        // Even if a caller somehow supplied coverage on an adjustment, the eligibility filter
        // drops the row before coverage is read.
        new EvidenceCoverage(List.copyOf(REQUIRED_OBJECTIVES),
            Set.of(MasteryDifficultyBand.MEDIUM)),
        "interaction", NOW, NOW);
    ledger.add(adjustment);

    assertThat(coveredBands(ledger))
        .as("the adjustment's MEDIUM must not count")
        .containsExactly(MasteryDifficultyBand.EASY);
    assertThat(statusOf(ledger)).isEqualTo(MasteryStatus.DEVELOPING);
  }

  // ---------------------------------------------------------------------------------------------
  // The same aggregation MasteryService performs.
  // ---------------------------------------------------------------------------------------------

  private MasteryStatus statusOf(List<Evidence> ledger) {
    MasteryOutcome outcome = mastery.compute(ledger, MASTERY_THRESHOLD, REQUIRED_EVIDENCE_COUNT);
    return statusPolicy.refine(outcome.status(), confidenceOf(ledger).confidence(),
        CONFIDENCE_THRESHOLD, REQUIRED_BANDS, coveredBands(ledger));
  }

  private ConfidenceOutcome confidenceOf(List<Evidence> ledger) {
    List<Evidence> observations = observations(ledger);
    return confidence.compute(new ConfidenceInputs(
        observations.stream().mapToInt(Evidence::itemsAnswered).sum(),
        REQUIRED_EVIDENCE_COUNT,
        coveredObjectives(ledger).size(),
        REQUIRED_OBJECTIVES.size(),
        0,
        observations.stream().map(Evidence::normalizedScore).toList()));
  }

  private static Set<UUID> coveredObjectives(List<Evidence> ledger) {
    Set<UUID> covered = new LinkedHashSet<>();
    for (Evidence observation : observations(ledger)) {
      observation.coverage().objectiveIds().stream()
          .filter(REQUIRED_OBJECTIVES::contains)
          .forEach(covered::add);
    }
    return covered;
  }

  private static Set<MasteryDifficultyBand> coveredBands(List<Evidence> ledger) {
    Set<MasteryDifficultyBand> covered = new LinkedHashSet<>();
    observations(ledger)
        .forEach(observation -> covered.addAll(observation.coverage().difficultyBands()));
    return covered;
  }

  private static List<Evidence> observations(List<Evidence> ledger) {
    return ledger.stream().filter(item -> !"ADJUSTMENT".equals(item.evidenceType())).toList();
  }

  /** Builds an observation the way the production path does: bands mapped from item difficulty. */
  private static Evidence observation(
      String type, String normalized, int itemsAnswered, int itemsCorrect,
      List<UUID> objectiveIds, AssessmentDifficulty difficulty) {
    return new Evidence(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type, "ASSESSMENT",
        UUID.randomUUID(), UUID.randomUUID(), DiagnosticScorer.SCORING_VERSION, null,
        "lineage:" + UUID.randomUUID(), new BigDecimal(normalized), new BigDecimal(normalized),
        itemsAnswered, itemsCorrect,
        new EvidenceCoverage(objectiveIds, Set.of(difficulty.band())),
        "interaction", NOW, NOW);
  }
}
