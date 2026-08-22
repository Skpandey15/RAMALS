package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticScorer;
import io.ramals.learningplatform.assessment.ScoredResponse;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.grounding.GroundingRetrievalPolicy;
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
import io.ramals.learningplatform.mastery.MasteryStatusPolicy;
import io.ramals.learningplatform.mastery.WeightedMasteryCalculator;
import io.ramals.learningplatform.recommendation.RecommendationPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
    put(MasteryStatusPolicy.POLICY_VERSION, EngineVersionFreezeTests::masteryStatus);
    put(ProgressionPolicy.POLICY_VERSION, EngineVersionFreezeTests::progression);
    put(RecommendationPolicy.POLICY_VERSION, EngineVersionFreezeTests::recommendation);
    put(DiagnosticScorer.SCORING_VERSION, EngineVersionFreezeTests::diagnosticScoring);
    put(LearningSessionPolicy.POLICY_VERSION, EngineVersionFreezeTests::sessionPolicy);
    put(GroundingRetrievalPolicy.V1.version(), EngineVersionFreezeTests::groundingRetrievalPolicy);
    put(ProposalGroundingPolicy.VERSION, EngineVersionFreezeTests::proposalGroundingPolicy);
  }};

  /**
   * SHA-256 over each engine's canonical output. Recorded when the control was frozen at
   * `v0.1.0-rc2`; see docs/release/mvp1-entry-plan.md §A2.
   */
  private static final Map<String, String> FROZEN = Map.of(
      "WEIGHTED_MASTERY_V1", "454b1443c92c1f1cca254e5141abf0a1750b92b2b7a0e430d12cd2f0503c7879",
      "EVIDENCE_CONFIDENCE_V1", "b98317c7dc259b63cd5fd9a7022f7adaf8c34a7154a1485b8dd5b93fc95fac7e",
      "MASTERY_STATUS_POLICY_V1", "5c57bb23ac7af54267a6b5c0f8ad629608523774088db75570a7bbdb83a84de7",
      "PROGRESSION_POLICY_V1", "08c765033f9a773c4603bc1760ede707cceaac1399937552a831beafbe1fb203",
      "RECOMMENDATION_POLICY_V1", "e048de44798cd9632934901b8354d5b50b036f8045cc66efcd6f229a24cdb212",
      "DIAGNOSTIC_SCORING_V1", "ee904dc57a615550d732e50bfd51fec72011db0e5b9a53a6f54c2d1d0ceda305",
      "SESSION_POLICY_V1", "195dbd7b65f733640229cac2b2fdc403e3d34350e9fd69f3a2e071a35da47647",
      "GROUNDING_RETRIEVAL_V1", "0ee0510ca9f6ec08721d4f5d476a0690dd4426abaf74a4aa0e4be11d2e8236ad",
      "PROPOSAL_GROUNDING_V1", "6578ca9a115acb2c2e9e7b11a872a94aa55614a0b321702a11cf63ba3c154a9a");

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
        new ScoredResponse("KAFKA_BROKER", 4, true),
        new ScoredResponse("KAFKA_BROKER", 4, false),
        new ScoredResponse("KAFKA_TOPIC", 3, true),
        new ScoredResponse("KAFKA_TOPIC", 3, true)))).append('\n');
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
        itemsAnswered, itemsAnswered, "interaction", Instant.EPOCH, Instant.EPOCH);
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
        EvidenceConfidenceCalculator.ALGORITHM_VERSION,
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
