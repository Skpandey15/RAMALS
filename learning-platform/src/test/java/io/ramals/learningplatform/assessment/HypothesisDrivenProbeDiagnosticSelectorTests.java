package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link HypothesisDrivenProbeDiagnosticSelector}: the pure signal/pool adjustment in isolation,
 * exercised with no database, plus a handful of cases run through the real, unmodified
 * {@link AdaptiveDiagnosticSelector#select} to prove the pool-restriction mechanism actually
 * produces the guarantees M2-ADR-025 claims for it (V3's cap respected, quota of one enforced)
 * without any special-casing in this class.
 */
class HypothesisDrivenProbeDiagnosticSelectorTests {

  private static final UUID SOURCE_ATTEMPT = UUID.randomUUID();
  private static final UUID PROBE_ITEM = UUID.randomUUID();
  private static final DiagnosticHypothesis HYPOTHESIS = new DiagnosticHypothesis(
      UUID.randomUUID(), UUID.randomUUID(), ProbeRelationshipType.ROOT_CAUSE_PROBE,
      UUID.randomUUID(), UUID.randomUUID());

  // -----------------------------------------------------------------------------------------
  // Frozen constants
  // -----------------------------------------------------------------------------------------

  @Test
  void probeQuotaIsFrozenAtOne() {
    assertThat(HypothesisDrivenProbeDiagnosticSelector.MAX_HYPOTHESIS_PROBES_PER_PACKET).isOne();
  }

  @Test
  void relationshipTypeTrialOrderIsFrozen() {
    assertThat(HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY).containsExactly(
        ProbeRelationshipType.ROOT_CAUSE_PROBE,
        ProbeRelationshipType.CONTRADICTION_CHECK,
        ProbeRelationshipType.PREREQUISITE_VALIDATION,
        ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION);
  }

  // -----------------------------------------------------------------------------------------
  // adjustForHypothesisProbe: the pure adjustment
  // -----------------------------------------------------------------------------------------

  @Test
  void aNullSelectionReturnsTheSameSignalsAndPoolInstances() {
    Map<String, SkillMasterySignal> signals = Map.of("KAFKA_BROKER", SkillMasterySignal.noEvidence());
    List<AdaptiveEligibleItem> pool = List.of(item("A1", "KAFKA_BROKER", "FOUNDATIONAL"));

    HypothesisDrivenProbeDiagnosticSelector.Adjusted adjusted =
        HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe(signals, pool, null);

    assertThat(adjusted.signals()).isSameAs(signals);
    assertThat(adjusted.pool()).isSameAs(pool);
  }

  @Test
  void aSelectionReprioritisesTheTargetSkillButKeepsWhateverBandUpstreamAlreadyDecided() {
    // Simulates arriving already capped by V3 (FOUNDATIONAL/PREREQUISITE_NOT_SECURED) -- V5 must
    // not re-escalate it, only change reason and priority, the same boundary V3/V4 already hold.
    SkillMasterySignal capped = new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.PREREQUISITE_NOT_SECURED, 5);
    Map<String, SkillMasterySignal> signals = Map.of("KAFKA_CONSUMER_GROUPS", capped);
    AdaptiveEligibleItem chosen = item("PROBE", "KAFKA_CONSUMER_GROUPS", "FOUNDATIONAL");
    List<AdaptiveEligibleItem> pool = List.of(chosen);
    HypothesisDrivenProbeDiagnosticSelector.Selection selection =
        new HypothesisDrivenProbeDiagnosticSelector.Selection(
            HYPOTHESIS, SOURCE_ATTEMPT, "KAFKA_CONSUMER_GROUPS", chosen.itemVersionId());

    HypothesisDrivenProbeDiagnosticSelector.Adjusted adjusted =
        HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe(signals, pool, selection);

    SkillMasterySignal result = adjusted.signals().get("KAFKA_CONSUMER_GROUPS");
    assertThat(result.targetDifficulty()).isEqualTo(AssessmentDifficulty.FOUNDATIONAL);
    assertThat(result.reason()).isEqualTo(SelectionReason.HYPOTHESIS_DRIVEN_PROBE);
    assertThat(result.priority()).isZero();
  }

  @Test
  void aTargetSkillAbsentFromBaseSignalsDefaultsToNoEvidenceBeforeOverride() {
    Map<String, SkillMasterySignal> signals = Map.of();
    AdaptiveEligibleItem chosen = item("PROBE", "KAFKA_ISR", "FOUNDATIONAL");
    HypothesisDrivenProbeDiagnosticSelector.Selection selection =
        new HypothesisDrivenProbeDiagnosticSelector.Selection(
            HYPOTHESIS, SOURCE_ATTEMPT, "KAFKA_ISR", chosen.itemVersionId());

    HypothesisDrivenProbeDiagnosticSelector.Adjusted adjusted = HypothesisDrivenProbeDiagnosticSelector
        .adjustForHypothesisProbe(signals, List.of(chosen), selection);

    SkillMasterySignal result = adjusted.signals().get("KAFKA_ISR");
    assertThat(result.targetDifficulty()).isEqualTo(SkillMasterySignal.noEvidence().targetDifficulty());
    assertThat(result.reason()).isEqualTo(SelectionReason.HYPOTHESIS_DRIVEN_PROBE);
  }

  @Test
  void everyOtherItemOfTheTargetSkillIsRemovedFromThePoolButOtherSkillsAreUntouched() {
    AdaptiveEligibleItem chosen = item("CHOSEN", "KAFKA_CONSUMER_GROUPS", "FOUNDATIONAL");
    AdaptiveEligibleItem otherOfSameSkill = item("OTHER", "KAFKA_CONSUMER_GROUPS", "INTERMEDIATE");
    AdaptiveEligibleItem unrelatedSkillItem = item("UNRELATED", "KAFKA_BROKER", "FOUNDATIONAL");
    List<AdaptiveEligibleItem> pool = List.of(chosen, otherOfSameSkill, unrelatedSkillItem);
    Map<String, SkillMasterySignal> signals = Map.of(
        "KAFKA_CONSUMER_GROUPS", SkillMasterySignal.noEvidence(),
        "KAFKA_BROKER", SkillMasterySignal.noEvidence());
    HypothesisDrivenProbeDiagnosticSelector.Selection selection =
        new HypothesisDrivenProbeDiagnosticSelector.Selection(
            HYPOTHESIS, SOURCE_ATTEMPT, "KAFKA_CONSUMER_GROUPS", chosen.itemVersionId());

    HypothesisDrivenProbeDiagnosticSelector.Adjusted adjusted =
        HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe(signals, pool, selection);

    assertThat(adjusted.pool()).containsExactly(chosen, unrelatedSkillItem);
  }

  @Test
  void v5OverridesV4sReasonOnTheSameSkillWhenAppliedAfterIt() {
    // M2-ADR-025 §5: precedence is a consequence of composition order, not special-case logic --
    // V4's own adjustForRegressions applied first, V5's adjustForHypothesisProbe applied second on
    // the same skill, and V5's reason/priority must be what survives.
    SkillMasterySignal v2Signal = new SkillMasterySignal(
        AssessmentDifficulty.INTERMEDIATE, SelectionReason.DIFFICULTY_PROGRESSION, 4);
    Map<String, SkillMasterySignal> baseSignals = Map.of("KAFKA_CONSUMER_GROUPS", v2Signal);
    Map<String, SkillMasterySignal> afterV4 = HypothesisConfirmationDiagnosticSelector
        .adjustForRegressions(baseSignals, java.util.Set.of("KAFKA_CONSUMER_GROUPS"));
    assertThat(afterV4.get("KAFKA_CONSUMER_GROUPS").reason())
        .isEqualTo(SelectionReason.HYPOTHESIS_CONFIRMATION);

    AdaptiveEligibleItem chosen = item("PROBE", "KAFKA_CONSUMER_GROUPS", "INTERMEDIATE");
    HypothesisDrivenProbeDiagnosticSelector.Selection selection =
        new HypothesisDrivenProbeDiagnosticSelector.Selection(
            HYPOTHESIS, SOURCE_ATTEMPT, "KAFKA_CONSUMER_GROUPS", chosen.itemVersionId());
    HypothesisDrivenProbeDiagnosticSelector.Adjusted afterV5 = HypothesisDrivenProbeDiagnosticSelector
        .adjustForHypothesisProbe(afterV4, List.of(chosen), selection);

    SkillMasterySignal result = afterV5.signals().get("KAFKA_CONSUMER_GROUPS");
    assertThat(result.reason()).isEqualTo(SelectionReason.HYPOTHESIS_DRIVEN_PROBE);
    assertThat(result.priority()).isZero();
    // The band V4 left untouched (V2's own INTERMEDIATE escalation) survives V5's override too --
    // V5 changes reason/priority only, on top of whatever V3/V4 already decided.
    assertThat(result.targetDifficulty()).isEqualTo(AssessmentDifficulty.INTERMEDIATE);
  }

  // -----------------------------------------------------------------------------------------
  // End to end through the real, unmodified V2 selector: the guarantees the pool restriction
  // is supposed to produce, proven rather than assumed.
  // -----------------------------------------------------------------------------------------

  @Test
  void v3sCapIsNeverSilentlyUndoneBecauseTheBandIsNeverTouched() {
    // The chosen candidate's own difficulty (ADVANCED) exceeds the band V3 already capped this
    // skill to (FOUNDATIONAL) -- V2's own "never present a band above what evidence earned" rule
    // must exclude it, with zero special-casing in HypothesisDrivenProbeDiagnosticSelector.
    AdaptiveDiagnosticFormProperties properties = new AdaptiveDiagnosticFormProperties();
    properties.setSingleChoiceTarget(1);
    properties.setFillBlankTarget(0);
    AdaptiveDiagnosticSelector selector = new AdaptiveDiagnosticSelector(properties);

    AdaptiveEligibleItem cappedSkillsOnlyItem = item("ADVANCED_ITEM", "KAFKA_CONSUMER_GROUPS", "ADVANCED");
    List<AdaptiveEligibleItem> pool = List.of(cappedSkillsOnlyItem);
    Map<String, SkillMasterySignal> signals = new LinkedHashMap<>();
    signals.put("KAFKA_CONSUMER_GROUPS", new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.PREREQUISITE_NOT_SECURED, 5));
    HypothesisDrivenProbeDiagnosticSelector.Selection selection =
        new HypothesisDrivenProbeDiagnosticSelector.Selection(
            HYPOTHESIS, SOURCE_ATTEMPT, "KAFKA_CONSUMER_GROUPS", cappedSkillsOnlyItem.itemVersionId());

    HypothesisDrivenProbeDiagnosticSelector.Adjusted adjusted = HypothesisDrivenProbeDiagnosticSelector
        .adjustForHypothesisProbe(signals, pool, selection);
    AdaptivePacket packet = selector.select(adjusted.pool(), adjusted.signals(), new Random(0));

    assertThat(packet.items()).isEmpty();
    assertThat(packet.skillsWithNoUnseenStock()).contains("KAFKA_CONSUMER_GROUPS");
  }

  @Test
  void theProbeQuotaOfOneIsEnforcedStructurallyAcrossMultipleRounds() {
    // A packet needing more items than there are skills forces multiple round-robin rounds; even
    // so, the restricted skill can contribute at most the one chosen item, because the pool has
    // nothing else left for it to give up in a later round.
    AdaptiveDiagnosticFormProperties properties = new AdaptiveDiagnosticFormProperties();
    properties.setSingleChoiceTarget(4);
    properties.setFillBlankTarget(0);
    AdaptiveDiagnosticSelector selector = new AdaptiveDiagnosticSelector(properties);

    AdaptiveEligibleItem chosen = item("CHOSEN", "KAFKA_CONSUMER_GROUPS", "FOUNDATIONAL");
    AdaptiveEligibleItem excluded = item("EXCLUDED", "KAFKA_CONSUMER_GROUPS", "FOUNDATIONAL");
    AdaptiveEligibleItem otherSkillItem1 = item("OTHER1", "KAFKA_BROKER", "FOUNDATIONAL");
    AdaptiveEligibleItem otherSkillItem2 = item("OTHER2", "KAFKA_TOPIC", "FOUNDATIONAL");
    List<AdaptiveEligibleItem> rawPool = List.of(chosen, excluded, otherSkillItem1, otherSkillItem2);
    Map<String, SkillMasterySignal> signals = new LinkedHashMap<>();
    signals.put("KAFKA_CONSUMER_GROUPS", SkillMasterySignal.noEvidence());
    signals.put("KAFKA_BROKER", SkillMasterySignal.noEvidence());
    signals.put("KAFKA_TOPIC", SkillMasterySignal.noEvidence());
    HypothesisDrivenProbeDiagnosticSelector.Selection selection =
        new HypothesisDrivenProbeDiagnosticSelector.Selection(
            HYPOTHESIS, SOURCE_ATTEMPT, "KAFKA_CONSUMER_GROUPS", chosen.itemVersionId());

    HypothesisDrivenProbeDiagnosticSelector.Adjusted adjusted =
        HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe(signals, rawPool, selection);
    AdaptivePacket packet = selector.select(adjusted.pool(), adjusted.signals(), new Random(0));

    long consumerGroupsPicks = packet.items().stream()
        .filter(selected -> selected.itemVersionId().equals(chosen.itemVersionId()))
        .count();
    assertThat(consumerGroupsPicks).isOne();
    assertThat(packet.items()).extracting(SelectedItem::itemVersionId)
        .doesNotContain(excluded.itemVersionId());
  }

  // -----------------------------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------------------------

  private static AdaptiveEligibleItem item(String seed, String skillCode, String difficulty) {
    UUID itemVersionId = UUID.nameUUIDFromBytes(seed.getBytes());
    UUID logicalItemId = UUID.nameUUIDFromBytes((seed + "-logical").getBytes());
    UUID skillId = UUID.nameUUIDFromBytes((skillCode + "-skill").getBytes());
    return new AdaptiveEligibleItem(itemVersionId, logicalItemId, skillId, skillCode, "SINGLE_CHOICE",
        difficulty);
  }
}
