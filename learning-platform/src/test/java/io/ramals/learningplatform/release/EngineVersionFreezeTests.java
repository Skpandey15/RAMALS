package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.AdaptiveDiagnosticFormProperties;
import io.ramals.learningplatform.assessment.AdaptiveDiagnosticSelector;
import io.ramals.learningplatform.assessment.AdaptiveEligibleItem;
import io.ramals.learningplatform.assessment.AdaptivePacket;
import io.ramals.learningplatform.assessment.AssessmentItemScoringView;
import io.ramals.learningplatform.assessment.DiagnosticForm;
import io.ramals.learningplatform.assessment.DiagnosticFormProperties;
import io.ramals.learningplatform.assessment.DiagnosticFormSelector;
import io.ramals.learningplatform.assessment.DiagnosticScorer;
import io.ramals.learningplatform.assessment.DiagnosticScorerV2;
import io.ramals.learningplatform.assessment.EligibleItem;
import io.ramals.learningplatform.assessment.SelectedItem;
import io.ramals.learningplatform.assessment.SelectionReason;
import io.ramals.learningplatform.assessment.ScoredResponse;
import io.ramals.learningplatform.assessment.SkillMasterySignal;
import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.ai.contract.AiEvaluatedResponseType;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationContext;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationRequest;
import io.ramals.learningplatform.ai.contract.AssessmentRubricDimension;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationProposal;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationProposal.Dimension;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DeterministicCheck;
import io.ramals.learningplatform.assessmentevaluation.EvaluationRubricScorePolicy;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DimensionResult;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextItem;
import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.GroundedContextValidator;
import io.ramals.learningplatform.grounding.GroundingRetrievalPolicy;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import io.ramals.learningplatform.orchestration.LearningWorkflowPolicy;
import io.ramals.learningplatform.grounding.ProposalGroundingPolicy;
import io.ramals.learningplatform.grounding.ProposalType;
import io.ramals.learningplatform.learning.LearningSessionCommand;
import io.ramals.learningplatform.learning.LearningSessionPolicy;
import io.ramals.learningplatform.learning.LearningSessionStatus;
import io.ramals.learningplatform.learning.ProgressionPolicy;
import io.ramals.learningplatform.mastery.ConfidenceInputs;
import io.ramals.learningplatform.mastery.EvidenceConfidenceCalculator;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.mastery.EvidenceConfidenceCalculatorV2;
import io.ramals.learningplatform.mastery.MasteryStatusPolicy;
import io.ramals.learningplatform.mastery.MasteryStatusPolicyV2;
import io.ramals.learningplatform.mastery.WeightedMasteryCalculator;
import io.ramals.learningplatform.recommendation.RecommendationPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Freezes the deterministic control (MVP-1 entry criterion 5).
 *
 * <p>Every consequential decision is stamped with a version identifier so a historical decision can
 * be reconstructed. That guarantee only holds if the behaviour behind an identifier never changes:
 * editing a `_V1` engine in place silently invalidates every record already written under it, and
 * nothing in the build would notice.
 *
 * <p>These tests hash each engine's output over fixed input vectors. Refactoring is free — renaming
 * a variable or extracting a method does not move the hash. Changing a weight, a threshold, a
 * rounding mode or a branch does.
 *
 * <p><b>If a hash assertion fails</b>, do not update the expected value. Either the change was
 * unintended and should be reverted, or it is intended — in which case mint a new identifier
 * (`..._V2`), leave `_V1` untouched so existing records stay reconstructable, and record an ADR.
 * Updating the constant in place is the one thing this test exists to prevent.
 */
class EngineVersionFreezeTests {

  /** Canonical output per version identifier. Deterministic, and independent of wall-clock time. */
  private static final Map<String, Supplier<String>> VECTORS = new LinkedHashMap<>() {{
    put(WeightedMasteryCalculator.ALGORITHM_VERSION, EngineVersionFreezeTests::weightedMastery);
    put(EvidenceConfidenceCalculator.ALGORITHM_VERSION, EngineVersionFreezeTests::evidenceConfidence);
    put(EvidenceConfidenceCalculatorV2.ALGORITHM_VERSION,
        EngineVersionFreezeTests::evidenceConfidenceV2);
    put(MasteryStatusPolicyV2.POLICY_VERSION, EngineVersionFreezeTests::masteryStatusV2);
    put(MasteryStatusPolicy.POLICY_VERSION, EngineVersionFreezeTests::masteryStatus);
    put(ProgressionPolicy.POLICY_VERSION, EngineVersionFreezeTests::progression);
    put(RecommendationPolicy.POLICY_VERSION, EngineVersionFreezeTests::recommendation);
    put(DiagnosticScorer.SCORING_VERSION, EngineVersionFreezeTests::diagnosticScoring);
    put(DiagnosticScorerV2.SCORING_VERSION, EngineVersionFreezeTests::diagnosticScoringV2);
    put(DiagnosticFormSelector.SELECTION_POLICY_VERSION,
        EngineVersionFreezeTests::diagnosticSelection);
    put(AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION,
        EngineVersionFreezeTests::adaptiveDiagnosticSelection);
    put(LearningSessionPolicy.POLICY_VERSION, EngineVersionFreezeTests::sessionPolicy);
    put(GroundingRetrievalPolicy.V1.version(), EngineVersionFreezeTests::groundingRetrievalPolicy);
    put(ProposalGroundingPolicy.VERSION, EngineVersionFreezeTests::proposalGroundingPolicy);
    put(EvaluationProposalGate.POLICY_VERSION, EngineVersionFreezeTests::evaluationProposalGate);
    put(EvaluationRubricScorePolicy.POLICY_VERSION, EngineVersionFreezeTests::evaluationRubricScore);
    put(LearningWorkflowPolicy.POLICY_VERSION, EngineVersionFreezeTests::learningWorkflowPolicy);
  }};

  /**
   * SHA-256 over each engine's canonical output. Recorded when the control was frozen at
   * `v0.1.0-rc2`; see docs/release/mvp1-entry-plan.md §A2.
   */
  private static final Map<String, String> FROZEN = Map.ofEntries(
      Map.entry("WEIGHTED_MASTERY_V1", "454b1443c92c1f1cca254e5141abf0a1750b92b2b7a0e430d12cd2f0503c7879"),
      Map.entry("EVIDENCE_CONFIDENCE_V1", "b98317c7dc259b63cd5fd9a7022f7adaf8c34a7154a1485b8dd5b93fc95fac7e"),
      // Minted with V046, when objective coverage became measurable. V1 above is untouched and
      // still hashes to the value recorded at v0.1.0-rc2, which is what proves the repair added a
      // policy rather than editing one.
      Map.entry("EVIDENCE_CONFIDENCE_V2", "b8d6a9ce0cd3899e3f09773b80d2d892971496f8533678b3bedafa938f9ac8a0"),
      Map.entry("MASTERY_STATUS_POLICY_V2", "3c24cf3961a952f41d8f5d78df4fb02b30a1da164f89cef8c863bab460bb7485"),
      Map.entry("MASTERY_STATUS_POLICY_V1", "5c57bb23ac7af54267a6b5c0f8ad629608523774088db75570a7bbdb83a84de7"),
      Map.entry("PROGRESSION_POLICY_V1", "08c765033f9a773c4603bc1760ede707cceaac1399937552a831beafbe1fb203"),
      Map.entry("RECOMMENDATION_POLICY_V1", "e048de44798cd9632934901b8354d5b50b036f8045cc66efcd6f229a24cdb212"),
      Map.entry("DIAGNOSTIC_SCORING_V1", "ee904dc57a615550d732e50bfd51fec72011db0e5b9a53a6f54c2d1d0ceda305"),
      // Minted with V047, when FILL_BLANK became a scoreable type. V1 above is untouched and still
      // hashes to its recorded value -- the SINGLE_CHOICE arithmetic did not change, only a scorer
      // that can additionally judge a type V1 was never asked to.
      Map.entry("DIAGNOSTIC_SCORING_V2", "8a722f4a6093016646081bfcd4b581e509e2e0dabdc16c4aec2ae6728106f87c"),
      // Recorded when form selection was first frozen, at the same moment its policy identifier
      // was minted. Nothing has been written under it yet, which is the only time an entry here
      // may be added rather than a new version identifier minted.
      Map.entry("DIAGNOSTIC_SELECTION_V1", "10a6138a21877206cfae74dce1717c81b644958fe3cd4e3ddc60e446ecf2a1a4"),
      // Minted with V050/PR-B, when the adaptive selector was first frozen. V1 above is untouched
      // and still hashes to its recorded value -- the coverage-pass arithmetic did not change, only
      // a different selector that reads mastery evidence instead.
      Map.entry("DIAGNOSTIC_SELECTION_V2", "5b7643a604b639e4d4f8176f39133a3ae713f2ce40b43d28f6274fefc8de7e23"),
      Map.entry("SESSION_POLICY_V1", "195dbd7b65f733640229cac2b2fdc403e3d34350e9fd69f3a2e071a35da47647"),
      Map.entry("GROUNDING_RETRIEVAL_V1", "0ee0510ca9f6ec08721d4f5d476a0690dd4426abaf74a4aa0e4be11d2e8236ad"),
      Map.entry("PROPOSAL_GROUNDING_V1", "6578ca9a115acb2c2e9e7b11a872a94aa55614a0b321702a11cf63ba3c154a9a"),
      Map.entry("EVALUATION_GATE_V1", "d0587299051f708dea7aa6d29e439b46f903d24b98d53c28dfc74a48bd00c0a3"),
      Map.entry("EVALUATION_SCORE_POLICY_V1", "b22b8a28e18da7a702816f894e027fe1acb470ea1b7c352adbaae5cd9d3a1a6a"),
      Map.entry("WORKFLOW_POLICY_V1", "7842607b4cad8420bab8aceabe300eaa6236deb59cd5c6954df736c8d24ad1e9"));

  @Test
  void everyVersionedEngineHasAFrozenVector() throws IOException {
    // Scans source rather than a hand-maintained list, so a new engine cannot be added without
    // being frozen. That is the failure mode this guards: coverage silently regressing.
    Set<String> declared = declaredVersionIdentifiers();
    assertThat(declared)
        .as("version identifiers found in main sources")
        .isNotEmpty();
    assertThat(VECTORS.keySet())
        .as("every versioned engine must have a frozen behaviour vector; add one to VECTORS")
        .containsAll(declared);
  }

  @Test
  void frozenEnginesStillProduceTheirRecordedBehaviour() {
    List<String> drifted = new ArrayList<>();
    VECTORS.forEach((version, vector) -> {
      String actual = sha256(vector.get());
      String expected = FROZEN.get(version);
      if (!actual.equals(expected)) {
        drifted.add(String.format("%n  %s%n    expected %s%n    actual   %s", version, expected, actual));
      }
    });
    assertThat(drifted)
        .as("""
            Frozen engine behaviour changed.

            Do NOT update the expected hash. Either revert the change, or mint a new version
            identifier (e.g. _V2), leave the existing one untouched so records already written
            under it stay reconstructable, and record an ADR.%s""", drifted)
        .isEmpty();
  }

  // --- canonical vectors -------------------------------------------------------------------------

  private static String weightedMastery() {
    WeightedMasteryCalculator calculator = new WeightedMasteryCalculator();
    StringBuilder out = new StringBuilder();
    // Each evidence type carries a distinct weight, so the mix is what the vector must pin down.
    List<List<Evidence>> cases = List.of(
        List.of(),
        List.of(evidence("DIAGNOSTIC", "0.80", 5)),
        List.of(evidence("DIAGNOSTIC", "0.80", 5), evidence("QUIZ", "0.60", 4)),
        List.of(evidence("PRACTICE", "1.00", 3), evidence("SCENARIO", "0.50", 2)),
        List.of(evidence("ADJUSTMENT", "0.10", 9), evidence("QUIZ", "0.75", 8)));
    for (List<Evidence> evidence : cases) {
      for (String threshold : List.of("0.70", "0.85")) {
        for (int required : List.of(1, 4)) {
          out.append(calculator.compute(evidence, new BigDecimal(threshold), required)).append('\n');
        }
      }
    }
    return out.toString();
  }

  private static String evidenceConfidence() {
    EvidenceConfidenceCalculator calculator = new EvidenceConfidenceCalculator();
    StringBuilder out = new StringBuilder();
    List<ConfidenceInputs> cases = List.of(
        new ConfidenceInputs(0, 5, 0, 4, 0, List.of()),
        new ConfidenceInputs(5, 5, 4, 4, 0, List.of(new BigDecimal("0.80"), new BigDecimal("0.80"))),
        new ConfidenceInputs(2, 5, 1, 4, 90, List.of(new BigDecimal("0.90"), new BigDecimal("0.10"))),
        new ConfidenceInputs(9, 5, 4, 4, 180, List.of(new BigDecimal("0.55"))),
        new ConfidenceInputs(9, 5, 4, 4, 400, List.of(new BigDecimal("0.55"))),
        new ConfidenceInputs(3, 0, 2, 0, 30, List.of(new BigDecimal("0.60"), new BigDecimal("0.65"))));
    for (ConfidenceInputs inputs : cases) {
      out.append(calculator.compute(inputs)).append('\n');
    }
    return out.toString();
  }

  private static String masteryStatus() {
    MasteryStatusPolicy policy = new MasteryStatusPolicy();
    StringBuilder out = new StringBuilder();
    Set<String> required = new TreeSet<>(Set.of("EASY", "MEDIUM", "HARD"));
    for (MasteryStatus status : MasteryStatus.values()) {
      for (String confidence : List.of("0.20", "0.75", "0.95")) {
        for (Set<String> covered : List.<Set<String>>of(
            Set.of(), Set.of("EASY"), Set.of("EASY", "MEDIUM"), Set.of("EASY", "MEDIUM", "HARD"))) {
          out.append(policy.refine(status, new BigDecimal(confidence), new BigDecimal("0.70"),
              required, covered)).append('\n');
        }
      }
    }
    return out.toString();
  }

  private static String progression() {
    ProgressionPolicy policy = new ProgressionPolicy();
    StringBuilder out = new StringBuilder();
    List<MasteryStatus> statuses = new ArrayList<>(List.of(MasteryStatus.values()));
    statuses.add(null); // an unassessed skill is a real input, not an accident
    for (MasteryStatus status : statuses) {
      for (boolean ready : List.of(true, false)) {
        for (boolean retentionDue : List.of(true, false)) {
          for (boolean regressed : List.of(true, false)) {
            out.append(policy.decide(status, ready, retentionDue, regressed)).append('\n');
          }
        }
      }
    }
    return out.toString();
  }

  private static String recommendation() {
    RecommendationPolicy policy = new RecommendationPolicy();
    StringBuilder out = new StringBuilder();
    for (MasteryStatus status : MasteryStatus.values()) {
      for (String score : List.of("0.10", "0.65", "0.99")) {
        out.append(policy.decide(snapshot(status, score))).append('\n');
      }
    }
    return out.toString();
  }

  private static String diagnosticScoring() {
    DiagnosticScorer scorer = new DiagnosticScorer();
    StringBuilder out = new StringBuilder();
    out.append(scorer.isCorrect(List.of("A"), List.of("A"))).append('\n');
    out.append(scorer.isCorrect(List.of("A"), List.of("B"))).append('\n');
    out.append(scorer.isCorrect(List.of("A", "B"), List.of("B", "A"))).append('\n');
    out.append(scorer.isCorrect(List.of("A"), List.of("A", "B"))).append('\n');
    out.append(scorer.isCorrect(List.of(), List.of("A"))).append('\n');

    out.append(scorer.aggregate(List.of())).append('\n');
    out.append(scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, false),
        new ScoredResponse("KAFKA_TOPIC", "SINGLE_CHOICE", 3, true),
        new ScoredResponse("KAFKA_TOPIC", "SINGLE_CHOICE", 3, true)))).append('\n');
    return out.toString();
  }

  /**
   * DiagnosticScorerV2 over the same SINGLE_CHOICE vectors V1 is frozen on, plus the behaviour V1
   * could never exercise: FILL_BLANK normalization and its zero guess-probability policy. The
   * first block proves the SINGLE_CHOICE arithmetic is unchanged; the second is what is new.
   */
  private static String diagnosticScoringV2() {
    DiagnosticScorerV2 scorer = new DiagnosticScorerV2();
    StringBuilder out = new StringBuilder();

    AssessmentItemScoringView mcq = new AssessmentItemScoringView(
        UUID.fromString("01900000-0000-7000-8000-00000000ff01"), "KAFKA_BROKER", "SINGLE_CHOICE",
        List.of("A", "B"), List.of("A"), List.of());
    out.append(scorer.isCorrect(mcq, List.of("A"))).append('\n');
    out.append(scorer.isCorrect(mcq, List.of("B"))).append('\n');

    AssessmentItemScoringView fillBlank = new AssessmentItemScoringView(
        UUID.fromString("01900000-0000-7000-8000-00000000ff02"), "KAFKA_TOPIC", "FILL_BLANK",
        List.of(), List.of(), List.of("partition", "the partition"));
    // Exact match, trimmed, case-insensitive, internal whitespace collapsed -- and, deliberately,
    // a near-miss that normalization must not paper over.
    out.append(scorer.isCorrect(fillBlank, List.of("partition"))).append('\n');
    out.append(scorer.isCorrect(fillBlank, List.of("  Partition  "))).append('\n');
    out.append(scorer.isCorrect(fillBlank, List.of("the   partition"))).append('\n');
    out.append(scorer.isCorrect(fillBlank, List.of("partitoin"))).append('\n');
    out.append(scorer.isCorrect(fillBlank, List.of("segment"))).append('\n');

    out.append(scorer.aggregate(List.of(
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, true),
        new ScoredResponse("KAFKA_BROKER", "SINGLE_CHOICE", 4, false),
        new ScoredResponse("KAFKA_TOPIC", "FILL_BLANK", 0, true),
        new ScoredResponse("KAFKA_TOPIC", "FILL_BLANK", 0, false)))).append('\n');
    return out.toString();
  }

  /**
   * Form selection over a fixed pool and fixed seeds. The selector takes its randomness as an
   * argument precisely so this is possible: a seeded {@link Random} makes every draw reproducible,
   * and a change to a coverage pass, a preference tiebreak, or the presentation shuffle moves the
   * hash even though the output is nominally random.
   */
  private static String diagnosticSelection() {
    DiagnosticFormProperties properties = new DiagnosticFormProperties();
    properties.setTargetSize(6);
    properties.setRecencyWindowDays(90);
    DiagnosticFormSelector selector = new DiagnosticFormSelector(properties);
    Instant now = Instant.parse("2026-08-15T00:00:00Z");
    List<EligibleItem> pool = List.of(
        eligibleItem(1, "KAFKA_BROKER", "FOUNDATIONAL", null),
        eligibleItem(2, "KAFKA_BROKER", "FOUNDATIONAL", now.minus(2, ChronoUnit.DAYS)),
        eligibleItem(3, "KAFKA_TOPIC", "FOUNDATIONAL", null),
        eligibleItem(4, "KAFKA_TOPIC", "INTERMEDIATE", now.minus(30, ChronoUnit.DAYS)),
        eligibleItem(5, "KAFKA_PARTITION", "INTERMEDIATE", null),
        eligibleItem(6, "KAFKA_PARTITION", "ADVANCED", now.minus(10, ChronoUnit.DAYS)),
        eligibleItem(7, "KAFKA_ACKS", "ADVANCED", null),
        eligibleItem(8, "KAFKA_ACKS", "FOUNDATIONAL", null));

    StringBuilder out = new StringBuilder();
    out.append(selector.recencyHorizon(now)).append('\n');
    for (int seed = 0; seed < 5; seed++) {
      DiagnosticForm form = selector.select(pool, new Random(seed));
      out.append(form.poolSize()).append('|')
          .append(form.skillsCovered()).append('|')
          .append(form.difficultiesCovered()).append('|')
          .append(form.recentlyPresentedReused()).append('\n');
      for (SelectedItem item : form.items()) {
        out.append(item.presentationOrder()).append(':')
            .append(item.itemVersionId()).append(':')
            .append(item.reason()).append('\n');
      }
    }
    return out.toString();
  }

  private static EligibleItem eligibleItem(
      int index, String skillCode, String difficulty, Instant lastPresentedAt) {
    return new EligibleItem(
        UUID.fromString("01900000-0000-7000-8000-00000000041" + index),
        skillCode, difficulty, lastPresentedAt);
  }

  /**
   * Adaptive selection over a fixed pool and fixed per-skill signals, one of every
   * {@link SelectionReason} the adaptive selector can emit -- unseen, low-confidence, weak,
   * objective-gap, progression, and mastery-confirmation -- so a change to priority ordering, band
   * fallback, or the type-quota round-robin moves the hash.
   */
  private static String adaptiveDiagnosticSelection() {
    AdaptiveDiagnosticFormProperties properties = new AdaptiveDiagnosticFormProperties();
    properties.setSingleChoiceTarget(5);
    properties.setFillBlankTarget(2);
    AdaptiveDiagnosticSelector selector = new AdaptiveDiagnosticSelector(properties);

    List<AdaptiveEligibleItem> pool = List.of(
        adaptiveItem(1, "KAFKA_BROKER", "SINGLE_CHOICE", "FOUNDATIONAL"),
        adaptiveItem(2, "KAFKA_BROKER", "SINGLE_CHOICE", "INTERMEDIATE"),
        adaptiveItem(3, "KAFKA_TOPIC", "SINGLE_CHOICE", "INTERMEDIATE"),
        adaptiveItem(4, "KAFKA_TOPIC", "FILL_BLANK", "FOUNDATIONAL"),
        adaptiveItem(5, "KAFKA_PARTITION", "SINGLE_CHOICE", "FOUNDATIONAL"),
        adaptiveItem(6, "KAFKA_PARTITION", "SINGLE_CHOICE", "INTERMEDIATE"),
        adaptiveItem(7, "KAFKA_PRODUCER_ACKS", "SINGLE_CHOICE", "FOUNDATIONAL"),
        adaptiveItem(8, "KAFKA_PRODUCER_ACKS", "SINGLE_CHOICE", "INTERMEDIATE"),
        adaptiveItem(9, "KAFKA_CONSUMER_GROUPS", "SINGLE_CHOICE", "FOUNDATIONAL"),
        adaptiveItem(10, "KAFKA_CONSUMER_GROUPS", "FILL_BLANK", "INTERMEDIATE"),
        adaptiveItem(11, "KAFKA_REPLICATION", "SINGLE_CHOICE", "ADVANCED"),
        adaptiveItem(12, "KAFKA_REPLICATION", "SINGLE_CHOICE", "INTERMEDIATE"));

    Map<String, SkillMasterySignal> signals = new LinkedHashMap<>();
    signals.put("KAFKA_BROKER", SkillMasterySignal.noEvidence());
    signals.put("KAFKA_TOPIC", new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.LOW_CONFIDENCE, 1));
    signals.put("KAFKA_PARTITION", new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.WEAK_SKILL, 2));
    signals.put("KAFKA_PRODUCER_ACKS", new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.OBJECTIVE_COVERAGE_GAP, 3));
    signals.put("KAFKA_CONSUMER_GROUPS", new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4));
    signals.put("KAFKA_REPLICATION", new SkillMasterySignal(
        AssessmentDifficulty.ADVANCED, SelectionReason.MASTERY_CONFIRMATION, 5));

    StringBuilder out = new StringBuilder();
    for (int seed = 0; seed < 5; seed++) {
      AdaptivePacket packet = selector.select(pool, signals, new Random(seed));
      out.append(packet.poolSize()).append('|')
          .append(packet.skillsCovered()).append('|')
          .append(packet.singleChoiceCount()).append('|')
          .append(packet.fillBlankCount()).append('|')
          .append(packet.skillsWithNoUnseenStock()).append('\n');
      for (SelectedItem item : packet.items()) {
        out.append(item.presentationOrder()).append(':')
            .append(item.itemVersionId()).append(':')
            .append(item.reason()).append('\n');
      }
    }
    return out.toString();
  }

  private static AdaptiveEligibleItem adaptiveItem(
      int index, String skillCode, String itemType, String difficulty) {
    UUID itemVersionId = UUID.fromString(String.format(
        "01900000-0000-7000-8000-0000000006%02d", index));
    UUID logicalItemId = UUID.fromString(String.format(
        "01900000-0000-7000-8000-0000000007%02d", index));
    UUID skillId = UUID.fromString(String.format(
        "01900000-0000-7000-8000-0000000001%02d", index));
    return new AdaptiveEligibleItem(itemVersionId, logicalItemId, skillId, skillCode, itemType,
        difficulty);
  }

  /**
   * V2 confidence over the same vectors V1 is frozen on, plus the case V1 could never reach: a
   * genuine objective coverage above zero. The first three rows therefore hash the claim that the
   * arithmetic is unchanged, and the last hashes the behaviour that is new.
   */
  private static String evidenceConfidenceV2() {
    EvidenceConfidenceCalculatorV2 calculator = new EvidenceConfidenceCalculatorV2();
    StringBuilder out = new StringBuilder();
    out.append(calculator.compute(new ConfidenceInputs(0, 5, 0, 0, 0, List.of()))).append('\n');
    out.append(calculator.compute(new ConfidenceInputs(
        1, 5, 0, 1, 0, List.of(new BigDecimal("1.0000"))))).append('\n');
    out.append(calculator.compute(new ConfidenceInputs(
        5, 5, 0, 1, 30, List.of(new BigDecimal("1.0000"), new BigDecimal("0.5000"),
            new BigDecimal("0.7500"), new BigDecimal("1.0000"), new BigDecimal("0.2500")))))
        .append('\n');
    out.append(calculator.compute(new ConfidenceInputs(
        5, 5, 2, 2, 0, List.of(new BigDecimal("1.0000"), new BigDecimal("1.0000"),
            new BigDecimal("1.0000"), new BigDecimal("1.0000"), new BigDecimal("1.0000")))))
        .append('\n');
    return out.toString();
  }

  /** V2 band gating across every combination of confident/not and covered/not. */
  private static String masteryStatusV2() {
    MasteryStatusPolicyV2 policy = new MasteryStatusPolicyV2();
    BigDecimal threshold = new BigDecimal("0.7500");
    Set<MasteryDifficultyBand> required =
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM);
    StringBuilder out = new StringBuilder();
    for (MasteryStatus provisional : MasteryStatus.values()) {
      for (String confidence : List.of("0.7400", "0.7500", "1.0000")) {
        for (Set<MasteryDifficultyBand> covered : List.of(
            Set.<MasteryDifficultyBand>of(),
            Set.of(MasteryDifficultyBand.EASY),
            Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM),
            Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM,
                MasteryDifficultyBand.HARD))) {
          out.append(provisional).append('|').append(confidence).append('|')
              .append(covered.stream().map(Enum::name).sorted().toList()).append("->")
              .append(policy.refine(
                  provisional, new BigDecimal(confidence), threshold, required, covered))
              .append('\n');
        }
      }
    }
    // A skill that requires no band is satisfied by nothing having been covered.
    out.append(policy.refine(MasteryStatus.MASTERED, new BigDecimal("1.0000"), threshold,
        Set.of(), Set.of())).append('\n');
    return out.toString();
  }

  private static String sessionPolicy() {
    LearningSessionPolicy policy = new LearningSessionPolicy();
    StringBuilder out = new StringBuilder();
    // Every (state, command) pair, including the ones that must be refused. A transition table is
    // exactly the kind of thing that drifts silently under refactoring.
    for (LearningSessionStatus from : LearningSessionStatus.values()) {
      for (LearningSessionCommand command : LearningSessionCommand.values()) {
        Optional<LearningSessionStatus> target = policy.target(from, command);
        out.append(from).append(' ').append(command).append(" -> ")
            .append(target.map(Enum::name).orElse("REJECTED")).append('\n');
      }
    }
    return out.toString();
  }

  private static String groundingRetrievalPolicy() {
    GroundingRetrievalPolicy policy = GroundingRetrievalPolicy.V1;
    return String.join("|", policy.version(), policy.freshness().toString(),
        policy.timeout().toString(), Integer.toString(policy.evidenceLimit()),
        Integer.toString(policy.masteryLimit()), Integer.toString(policy.skillGraphLimit()),
        Integer.toString(policy.curriculumPolicyLimit()),
        Integer.toString(policy.approvedContentLimit()));
  }

  private static String proposalGroundingPolicy() {
    ProposalGroundingPolicy policy = new ProposalGroundingPolicy();
    StringBuilder out = new StringBuilder();
    for (ProposalType type : ProposalType.values()) {
      out.append(type).append('|').append(policy.minimumConfidence(type)).append('|')
          .append(new TreeSet<>(policy.requiredSources(type))).append('|')
          .append(new TreeSet<>(policy.claimEvidenceSources(type))).append('\n');
    }
    return out.toString();
  }

  private static String evaluationProposalGate() {
    Instant now = Instant.parse("2026-08-23T03:00:00Z");
    GroundedContext context =
        new GroundedContext(
            GroundedContext.CONTRACT_VERSION,
            "evaluation-context-v1",
            "opaque-learner",
            now,
            now.plusSeconds(600),
            EvaluationProposalGate.REQUEST_POLICY,
            List.of(
                new GroundedContextItem(
                    "answer-evidence",
                    SourceType.ASSESSMENT,
                    "answer-v1",
                    ContextAuthority.AUTHORITATIVE_FACT,
                    "ANSWER_VERSION",
                    "answer-v1",
                    now,
                    null),
                new GroundedContextItem(
                    "rubric-evidence",
                    SourceType.ASSESSMENT,
                    "rubric-v1",
                    ContextAuthority.AUTHORITATIVE_FACT,
                    "RUBRIC_DIMENSION",
                    "accuracy",
                    now,
                    null)));
    AssessmentEvaluationContext evaluation =
        new AssessmentEvaluationContext(
            AiEvaluatedResponseType.FREE_TEXT,
            "answer-v1",
            "rubric-v1",
            "answer-evidence",
            "A bounded answer.",
            List.of(
                new AssessmentRubricDimension(
                    "accuracy", new BigDecimal("4"), "Approved criteria.", "rubric-evidence")));
    AssessmentEvaluationRequest request =
        new AssessmentEvaluationRequest(
            AssessmentEvaluationRequest.CONTRACT_VERSION,
            "interaction-v1",
            "request-v1",
            new Constraints(
                InteractionClass.ASSESSMENT_PROPOSAL,
                8_000,
                1_200,
                List.of(),
                EvaluationProposalGate.REQUEST_POLICY),
            evaluation,
            context);
    EvaluationProposalGate gate =
        new EvaluationProposalGate(
            new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build()));
    StringBuilder out = new StringBuilder();
    for (String score : List.of("0", "4", "5")) {
      for (String confidence : List.of("0.6900", "0.7000")) {
        for (DeterministicCheck deterministic :
            List.of(
                DeterministicCheck.notApplicable(),
                DeterministicCheck.agrees("DETERMINISTIC_AGREES"),
                DeterministicCheck.conflicts("DETERMINISTIC_CONFLICT"))) {
          AssessmentEvaluationProposal proposal =
              new AssessmentEvaluationProposal(
                  AssessmentEvaluationProposal.CONTRACT_VERSION,
                  "proposal-v1",
                  "request-v1",
                  "run-v1",
                  context.contextId(),
                  evaluation.answerVersion(),
                  evaluation.rubricVersion(),
                  List.of(
                      new Dimension(
                          "accuracy",
                          new BigDecimal(score),
                          new BigDecimal("4"),
                          "Grounded dimension feedback.",
                          Set.of("answer-evidence", "rubric-evidence"))),
                  "Grounded overall feedback.",
                  Set.of("answer-evidence"),
                  new BigDecimal(confidence));
          var decision = gate.evaluate(proposal, request, deterministic, now);
          out.append(score)
              .append('|')
              .append(confidence)
              .append('|')
              .append(deterministic.comparison())
              .append('|')
              .append(decision.outcome())
              .append('|')
              .append(decision.reasons())
              .append('|')
              .append(decision.allowsAuthoritativeEffect())
              .append('\n');
        }
      }
    }
    return out.toString();
  }

  private static String learningWorkflowPolicy() {
    StringBuilder out = new StringBuilder();
    for (EvaluationProposalGate.Outcome outcome : EvaluationProposalGate.Outcome.values()) {
      for (String score : List.of("-0.0001", "0", "0.6000", "1", "1.0001")) {
        var eligibility =
            LearningWorkflowPolicy.evaluationEligible(outcome, new BigDecimal(score));
        out.append("evaluation|").append(outcome).append('|').append(score).append('|')
            .append(eligibility.eligible()).append('|').append(eligibility.reasonCode())
            .append('\n');
      }
    }
    for (MasteryStatus status : MasteryStatus.values()) {
      var eligibility = LearningWorkflowPolicy.diagnosisEligible(status);
      out.append("diagnosis|").append(status).append('|').append(eligibility.eligible())
          .append('|').append(eligibility.reasonCode()).append('\n');
    }
    for (boolean accepted : List.of(true, false)) {
      var eligibility = LearningWorkflowPolicy.adaptationEligible(accepted);
      out.append("adaptation|").append(accepted).append('|').append(eligibility.eligible())
          .append('|').append(eligibility.reasonCode()).append('\n');
    }
    for (int attempt = 0; attempt <= LearningWorkflowPolicy.MAX_STEP_ATTEMPTS + 1; attempt++) {
      out.append("retry|").append(attempt).append('|')
          .append(LearningWorkflowPolicy.mayRetry(attempt)).append('\n');
    }
    for (Step step : Step.values()) {
      out.append("step|").append(step).append('|').append(step.index()).append('|')
          .append(step.invokesAgent()).append('|')
          .append(LearningWorkflowPolicy.next(step).map(Enum::name).orElse("END")).append('\n');
    }
    out.append("deadlineSeconds|").append(LearningWorkflowPolicy.RUN_DEADLINE.toSeconds())
        .append('\n');
    return out.toString();
  }

  private static String evaluationRubricScore() {
    StringBuilder out = new StringBuilder();
    List<List<DimensionResult>> cases = List.of(
        List.of(new DimensionResult("accuracy", new BigDecimal("3"), new BigDecimal("4"),
            "feedback", Set.of("answer", "rubric"))),
        List.of(
            new DimensionResult("accuracy", new BigDecimal("1"), new BigDecimal("4"),
                "feedback", Set.of("answer", "rubric")),
            new DimensionResult("reasoning", new BigDecimal("4"), new BigDecimal("4"),
                "feedback", Set.of("answer", "rubric"))),
        List.of(new DimensionResult("accuracy", new BigDecimal("1"), new BigDecimal("3"),
            "feedback", Set.of("answer", "rubric"))),
        List.of(new DimensionResult("accuracy", new BigDecimal("0"), new BigDecimal("4"),
            "feedback", Set.of("answer", "rubric"))));
    for (List<DimensionResult> dimensions : cases) {
      out.append(EvaluationRubricScorePolicy.normalizedScore(dimensions)).append('\n');
    }
    return out.toString();
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static Evidence evidence(String type, String normalizedScore, int itemsAnswered) {
    return new Evidence(
        UUID.fromString("01900000-0000-7000-8000-00000000000e"),
        UUID.fromString("01900000-0000-7000-8000-00000000000a"),
        UUID.fromString("01900000-0000-7000-8000-00000000000b"),
        type, "ASSESSMENT",
        UUID.fromString("01900000-0000-7000-8000-00000000000c"),
        UUID.fromString("01900000-0000-7000-8000-00000000000d"),
        DiagnosticScorer.SCORING_VERSION, null, "lineage",
        new BigDecimal(normalizedScore), new BigDecimal(normalizedScore),
        itemsAnswered, itemsAnswered, EvidenceCoverage.none(), "interaction",
        Instant.EPOCH, Instant.EPOCH);
  }

  private static MasterySnapshot snapshot(MasteryStatus status, String score) {
    return new MasterySnapshot(
        UUID.fromString("01900000-0000-7000-8000-0000000000f1"),
        UUID.fromString("01900000-0000-7000-8000-0000000000f2"),
        UUID.fromString("01900000-0000-7000-8000-0000000000f3"),
        UUID.fromString("01900000-0000-7000-8000-0000000000f4"),
        1, new BigDecimal(score), status, new BigDecimal("0.70"),
        new BigDecimal("0.80"), new BigDecimal("0.70"), 4, 12,
        WeightedMasteryCalculator.ALGORITHM_VERSION,
        EvidenceConfidenceCalculator.ALGORITHM_VERSION, MasteryStatusPolicy.POLICY_VERSION,
        null, java.util.Set.of(),
        "interaction", Instant.EPOCH);
  }

  private static final Pattern VERSION_CONSTANT = Pattern.compile(
      "static\\s+final\\s+String\\s+\\w*VERSION\\s*=\\s*\"([A-Z][A-Z0-9_]*_V\\d+)\"");

  private static Set<String> declaredVersionIdentifiers() throws IOException {
    Path root = Path.of("src", "main", "java");
    if (!Files.isDirectory(root)) {
      root = Path.of("learning-platform", "src", "main", "java");
    }
    Set<String> found = new TreeSet<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        Matcher matcher = VERSION_CONSTANT.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find()) {
          found.add(matcher.group(1));
        }
      }
    }
    return found;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }
}
