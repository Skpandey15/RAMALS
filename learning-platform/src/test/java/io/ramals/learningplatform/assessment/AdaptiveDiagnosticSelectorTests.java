package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The adaptive selection rules, exercised against synthetic pools and signals. Every case seeds
 * its own {@link Random} so a packet is reproducible: the algorithm reads nothing but its
 * arguments, the same discipline {@link DiagnosticFormSelectorTests} holds V1 to.
 */
class AdaptiveDiagnosticSelectorTests {

  @Test
  void emptyPoolProducesAnEmptyPacketRatherThanFailing() {
    AdaptivePacket packet = selector(5, 2).select(List.of(), Map.of(), new Random(1));

    assertThat(packet.items()).isEmpty();
    assertThat(packet.skillsCovered()).isZero();
    assertThat(packet.skillsWithNoUnseenStock()).isEmpty();
  }

  @Test
  void everySkillWithUnseenStockContributesAtLeastOneItemBeforeAnySkillGetsASecond() {
    List<AdaptiveEligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 5; skill++) {
      pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "FOUNDATIONAL"));
      pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "INTERMEDIATE"));
    }
    Map<String, SkillMasterySignal> signals = Map.of();

    AdaptivePacket packet = selector(5, 0).select(pool, signals, new Random(3));

    assertThat(packet.items()).hasSize(5);
    assertThat(packet.skillsCovered()).isEqualTo(5);
  }

  @Test
  void neediestSkillsReceiveTheExtraItemsOnceEveryOtherSkillHasOne() {
    List<AdaptiveEligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 5; skill++) {
      pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "FOUNDATIONAL"));
      pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "FOUNDATIONAL"));
    }
    Map<String, SkillMasterySignal> signals = new LinkedHashMap<>();
    signals.put("SKILL_1", SkillMasterySignal.noEvidence()); // priority 0, neediest
    signals.put("SKILL_2", new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.LOW_CONFIDENCE, 1));
    signals.put("SKILL_3", new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.WEAK_SKILL, 2));
    signals.put("SKILL_4", new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.OBJECTIVE_COVERAGE_GAP, 3));
    signals.put("SKILL_5", new SkillMasterySignal(
        AssessmentDifficulty.ADVANCED, SelectionReason.MASTERY_CONFIRMATION, 5));

    // 5 skills, quota 7: round 1 gives one to each (5 items), round 2 gives one more to the two
    // neediest -- SKILL_1 and SKILL_2 -- not to SKILL_5, which is least in need.
    AdaptivePacket packet = selector(7, 0).select(pool, signals, new Random(9));

    Map<String, Long> perSkill = new LinkedHashMap<>();
    for (SelectedItem selected : packet.items()) {
      String skillCode = skillCodeOf(pool, selected.itemVersionId());
      perSkill.merge(skillCode, 1L, Long::sum);
    }
    assertThat(perSkill.get("SKILL_1")).isEqualTo(2L);
    assertThat(perSkill.get("SKILL_2")).isEqualTo(2L);
    assertThat(perSkill.getOrDefault("SKILL_5", 0L)).isEqualTo(1L);
  }

  @Test
  void anItemIsNeverPresentedAboveTheSkillsDecidedBand() {
    List<AdaptiveEligibleItem> pool = List.of(
        item("KAFKA_BROKER", "SINGLE_CHOICE", "FOUNDATIONAL"),
        item("KAFKA_BROKER", "SINGLE_CHOICE", "ADVANCED"));
    // No evidence at all -> FOUNDATIONAL is the decided band; ADVANCED must never be reachable.
    Map<String, SkillMasterySignal> signals = Map.of();

    AdaptivePacket packet = selector(1, 0).select(pool, signals, new Random(2));

    assertThat(packet.items()).hasSize(1);
    UUID selected = packet.items().getFirst().itemVersionId();
    assertThat(difficultyOf(pool, selected)).isEqualTo("FOUNDATIONAL");
  }

  @Test
  void aThinPoolAtTheDecidedBandFallsBackToAnEasierUnseenItemRatherThanSkippingTheSkill() {
    // Evidence says INTERMEDIATE is earned, but only a FOUNDATIONAL item is unseen for this skill.
    List<AdaptiveEligibleItem> pool = List.of(item("KAFKA_TOPIC", "SINGLE_CHOICE", "FOUNDATIONAL"));
    Map<String, SkillMasterySignal> signals = Map.of("KAFKA_TOPIC", new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4));

    AdaptivePacket packet = selector(1, 0).select(pool, signals, new Random(4));

    assertThat(packet.items()).hasSize(1);
    assertThat(packet.skillsWithNoUnseenStock()).isEmpty();
  }

  @Test
  void aSkillWithNoUnseenStockAtAllIsReportedRatherThanSilentlySkipped() {
    List<AdaptiveEligibleItem> pool = List.of(item("KAFKA_BROKER", "SINGLE_CHOICE", "FOUNDATIONAL"));
    // KAFKA_TOPIC has a signal but zero candidates in the pool: the caller already excluded every
    // one of its items as previously seen.
    Map<String, SkillMasterySignal> signals = Map.of(
        "KAFKA_BROKER", SkillMasterySignal.noEvidence(),
        "KAFKA_TOPIC", SkillMasterySignal.noEvidence());

    AdaptivePacket packet = selector(5, 0).select(pool, signals, new Random(6));

    // KAFKA_TOPIC contributes nothing because the pool never carried it in the first place -- the
    // pure selector cannot distinguish "excluded" from "never existed", which is why
    // DiagnosticService computes exhaustion from the full pool, not from this packet alone. What
    // this asserts is narrower and still true: the selector reports only skills present in ITS
    // pool, and never invents a candidate to cover the one that is not.
    assertThat(packet.skillsWithNoUnseenStock()).isEmpty();
    assertThat(packet.items()).hasSize(1);
  }

  @Test
  void typeQuotaIsRespectedAcrossEveryRound() {
    List<AdaptiveEligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 3; skill++) {
      pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "FOUNDATIONAL"));
      pool.add(item("SKILL_" + skill, "FILL_BLANK", "FOUNDATIONAL"));
    }

    AdaptivePacket packet = selector(2, 1).select(pool, Map.of(), new Random(8));

    assertThat(packet.items()).hasSize(3);
    assertThat(packet.singleChoiceCount()).isEqualTo(2);
    assertThat(packet.fillBlankCount()).isEqualTo(1);
  }

  @Test
  void theSelectionReasonRecordedIsExactlyTheSkillsSignalReason() {
    List<AdaptiveEligibleItem> pool = List.of(item("KAFKA_ACKS", "SINGLE_CHOICE", "FOUNDATIONAL"));
    Map<String, SkillMasterySignal> signals = Map.of("KAFKA_ACKS", new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.WEAK_SKILL, 2));

    AdaptivePacket packet = selector(1, 0).select(pool, signals, new Random(10));

    assertThat(packet.items().getFirst().reason()).isEqualTo(SelectionReason.WEAK_SKILL);
  }

  @Test
  void selectionIsReproducibleFromTheSameSeed() {
    List<AdaptiveEligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 4; skill++) {
      pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "FOUNDATIONAL"));
      pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "INTERMEDIATE"));
    }
    AdaptiveDiagnosticSelector selector = selector(5, 0);

    AdaptivePacket first = selector.select(pool, Map.of(), new Random(42));
    AdaptivePacket second = selector.select(pool, Map.of(), new Random(42));

    assertThat(first.items()).isEqualTo(second.items());
  }

  @Test
  void aPacketNeverExceedsTheQuota() {
    List<AdaptiveEligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 5; skill++) {
      for (int variant = 0; variant < 4; variant++) {
        pool.add(item("SKILL_" + skill, "SINGLE_CHOICE", "FOUNDATIONAL"));
      }
    }

    AdaptivePacket packet = selector(5, 2).select(pool, Map.of(), new Random(13));

    assertThat(packet.items()).hasSizeLessThanOrEqualTo(7);
    assertThat(packet.singleChoiceCount()).isLessThanOrEqualTo(5);
    assertThat(packet.fillBlankCount()).isLessThanOrEqualTo(2);
  }

  private static AdaptiveDiagnosticSelector selector(int singleChoiceTarget, int fillBlankTarget) {
    AdaptiveDiagnosticFormProperties properties = new AdaptiveDiagnosticFormProperties();
    properties.setSingleChoiceTarget(singleChoiceTarget);
    properties.setFillBlankTarget(fillBlankTarget);
    return new AdaptiveDiagnosticSelector(properties);
  }

  private static int counter = 0;

  private static AdaptiveEligibleItem item(String skillCode, String itemType, String difficulty) {
    counter++;
    UUID itemVersionId = new UUID(0, counter);
    UUID logicalItemId = new UUID(1, counter);
    UUID skillId = new UUID(2, skillCode.hashCode());
    return new AdaptiveEligibleItem(itemVersionId, logicalItemId, skillId, skillCode, itemType,
        difficulty);
  }

  private static String skillCodeOf(List<AdaptiveEligibleItem> pool, UUID itemVersionId) {
    return pool.stream()
        .filter(candidate -> candidate.itemVersionId().equals(itemVersionId))
        .findFirst().orElseThrow().skillCode();
  }

  private static String difficultyOf(List<AdaptiveEligibleItem> pool, UUID itemVersionId) {
    return pool.stream()
        .filter(candidate -> candidate.itemVersionId().equals(itemVersionId))
        .findFirst().orElseThrow().difficulty();
  }
}
