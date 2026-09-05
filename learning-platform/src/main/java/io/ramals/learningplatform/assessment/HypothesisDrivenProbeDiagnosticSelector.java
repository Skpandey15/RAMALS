package io.ramals.learningplatform.assessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DIAGNOSTIC_SELECTION_V5 (M2-ADR-025): the runtime consumer of H4b's read-only foundation
 * (#251, M2-ADR-024) -- composes with V3 and V4 the same way they compose with V2: one more
 * adjustment step applied to the signal map (and, uniquely among V3/V4/V5, the pool) before either
 * ever reaches {@link AdaptiveDiagnosticSelector#select}, which stays completely unmodified.
 *
 * <p><b>V2 is skill-grained; H4b is objective/item-grained -- this class is the bridge.</b>
 * {@code AdaptiveDiagnosticSelector} only understands "which skill, at which band"; the item
 * actually chosen within a skill/band is resolved by its own per-call random tiebreak, which this
 * class has no hook into and must not reach for. So rather than trying to express "prefer this
 * specific item" as a new kind of signal, {@link #adjustForHypothesisProbe} restricts the *pool*:
 * every other item of the one target skill is removed, leaving the chosen H4b candidate as the only
 * thing V2's own unmodified {@code bestCandidate()} can possibly pick for that skill. This is why
 * V3's band cap is never silently undone (§ below) and why the probe quota is exactly one, with zero
 * special-casing for either: both are consequences of restricting V2's input, not new logic reading
 * V3's decision or counting probes served. See M2-ADR-025 for the full reasoning.
 *
 * <p><b>{@code targetDifficulty} is never touched.</b> Only {@code reason} and {@code priority}
 * change for the target skill's signal -- exactly the same boundary
 * {@link PrerequisiteAwareDiagnosticSelector} and {@link HypothesisConfirmationDiagnosticSelector}
 * already hold. If the chosen candidate's own difficulty exceeds whatever band V3/V4 already
 * decided for that skill, V2's own "never present a band above what evidence earned" rule excludes
 * it, and that skill simply contributes nothing this round -- not a bypass of the cap, a direct
 * consequence of never touching it.
 *
 * <p><b>Deciding which prior miss and which relationship type to try is not this class's job.</b>
 * That is {@code DiagnosticService}'s job -- reading the immediately preceding completed attempt,
 * walking its misses in {@code presentation_order}, and calling
 * {@code ProbeRelationshipService.resolve} in {@link #RELATIONSHIP_TYPE_PRIORITY} order -- resolved
 * once into a single {@link Selection} (or {@code null}) before this class ever runs, the same
 * separation {@link HypothesisConfirmationDiagnosticSelector} keeps from the snapshot-history walk
 * that feeds it.
 */
public final class HypothesisDrivenProbeDiagnosticSelector {

  public static final String SELECTION_POLICY_VERSION = "DIAGNOSTIC_SELECTION_V5";

  /** Conservative on purpose (M2-ADR-025 §6): broad skill coverage stays the default, one targeted
   * probe per attempt is the whole of this capability's ambition. Enforced structurally by
   * {@link #adjustForHypothesisProbe}'s pool restriction, not by counting anything. */
  public static final int MAX_HYPOTHESIS_PROBES_PER_PACKET = 1;

  /** The frozen order {@code DiagnosticService} tries {@link ProbeRelationshipType} values in for
   * one miss. {@code ROOT_CAUSE_PROBE} first: the hand-authored, flagship semantic H4b's own
   * narrative is built around. {@code SAME_OBJECTIVE_CONFIRMATION} last: weakest specificity,
   * "ask about the same thing again." Changing this order changes V5's trigger policy and needs a
   * new version, the same discipline every other frozen rule in this codebase is held to. */
  public static final List<ProbeRelationshipType> RELATIONSHIP_TYPE_PRIORITY = List.of(
      ProbeRelationshipType.ROOT_CAUSE_PROBE,
      ProbeRelationshipType.CONTRADICTION_CHECK,
      ProbeRelationshipType.PREREQUISITE_VALIDATION,
      ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION);

  /** Tied with UNSEEN_ITEM/HYPOTHESIS_CONFIRMATION: a resolved, evidence-seeking probe is at least
   * as urgent as any routine coverage pick. Composition order (V3 -> V4 -> V5) is what makes V5 win
   * over V4 on a shared skill, not this value alone -- see M2-ADR-025 §5. */
  private static final int PRIORITY_HYPOTHESIS_DRIVEN_PROBE = 0;

  private HypothesisDrivenProbeDiagnosticSelector() {
  }

  /**
   * @param hypothesis the hypothesis that authorized this probe -- carried through purely for
   *     provenance; not read by {@link #adjustForHypothesisProbe} itself
   * @param sourceAttemptId the prior attempt whose miss raised {@code hypothesis} -- also
   *     provenance-only
   * @param targetSkillCode the skill {@code chosenItemVersionId} belongs to
   * @param chosenItemVersionId the single H4b candidate item chosen to be prioritized
   */
  public record Selection(
      DiagnosticHypothesis hypothesis, UUID sourceAttemptId, String targetSkillCode,
      UUID chosenItemVersionId) {
  }

  /** The two V2 inputs after V5's adjustment -- signals with the target skill reprioritised, and a
   * pool with that skill's other items removed. */
  public record Adjusted(
      Map<String, SkillMasterySignal> signals, List<AdaptiveEligibleItem> pool) {
  }

  /**
   * @param baseSignals signals already adjusted by V3 (prerequisites) and V4 (regressions)
   * @param pool the same unseen pool V2 will select from
   * @param selection the single resolved probe to prioritize, or {@code null} if V5 found none --
   *     in which case {@code baseSignals} and {@code pool} are returned unchanged (same instances),
   *     the same convention {@link HypothesisConfirmationDiagnosticSelector#adjustForRegressions}
   *     already holds for an empty adjustment
   */
  public static Adjusted adjustForHypothesisProbe(
      Map<String, SkillMasterySignal> baseSignals, List<AdaptiveEligibleItem> pool, Selection selection) {
    if (selection == null) {
      return new Adjusted(baseSignals, pool);
    }

    SkillMasterySignal base = baseSignals.getOrDefault(
        selection.targetSkillCode(), SkillMasterySignal.noEvidence());
    Map<String, SkillMasterySignal> adjustedSignals = new LinkedHashMap<>(baseSignals);
    adjustedSignals.put(selection.targetSkillCode(), new SkillMasterySignal(
        base.targetDifficulty(), SelectionReason.HYPOTHESIS_DRIVEN_PROBE,
        PRIORITY_HYPOTHESIS_DRIVEN_PROBE));

    List<AdaptiveEligibleItem> adjustedPool = new ArrayList<>(pool.size());
    for (AdaptiveEligibleItem item : pool) {
      boolean isAnotherItemOfTheTargetSkill = item.skillCode().equals(selection.targetSkillCode())
          && !item.itemVersionId().equals(selection.chosenItemVersionId());
      if (!isAnotherItemOfTheTargetSkill) {
        adjustedPool.add(item);
      }
    }

    return new Adjusted(adjustedSignals, adjustedPool);
  }
}
