package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DIAGNOSTIC_SELECTION_V3: what {@link AdaptiveDiagnosticSelector} (V2) adds nothing about --
 * whether a skill's own evidence can be trusted enough to escalate it, given what the curriculum's
 * prerequisite graph says about the skills it depends on. Per M2-ADR-023.
 *
 * <p><b>Not a selector in its own right.</b> This class does not choose items, rank skills, or run
 * a round-robin -- {@link AdaptiveDiagnosticSelector} still does every one of those, completely
 * unmodified. V3 is V2's already-frozen mechanics plus exactly one new step, applied to the signal
 * map before it ever reaches {@link AdaptiveDiagnosticSelector#select}:
 * {@link #adjustForPrerequisites}. The composition -- adjust, then delegate to V2's {@code select}
 * -- is what {@code DIAGNOSTIC_SELECTION_V3} names as a whole; see
 * {@code DiagnosticService#selectPrerequisiteAwareForm}.
 *
 * <p><b>Evidence, never a gate (M2-ADR-023 §1).</b> An unsecured prerequisite never removes a skill
 * from selection. It only caps the band a skill's own signal would otherwise escalate to, and
 * lowers that skill's priority so the packet favours skills whose evidence can actually be trusted
 * right now -- among them, very often, the unsecured prerequisite itself, whose own signal already
 * reflects its own weakness independently of anything this class does.
 *
 * <p><b>"Secured" means MASTERED, nothing weaker</b> -- the same bar {@code GapDiagnosisService}
 * already holds prerequisites to, not a second one invented here.
 *
 * <p><b>Only a band above FOUNDATIONAL is ever capped.</b> A signal already held at FOUNDATIONAL
 * (an unseen skill, or one already being held back for its own reasons -- low confidence, a weak
 * score, incomplete objective coverage) is left untouched: there is nothing to cap, and relabelling
 * it would manufacture a claim about prerequisites that was never the actual reason for the hold.
 */
public final class PrerequisiteAwareDiagnosticSelector {

  public static final String SELECTION_POLICY_VERSION = "DIAGNOSTIC_SELECTION_V3";

  /** Shares MASTERY_CONFIRMATION's tier: a skill capped for this reason is, like a mastered one,
   * not where the next unit of evidence is most needed right now. */
  private static final int DEMOTED_PRIORITY = 5;

  private PrerequisiteAwareDiagnosticSelector() {
  }

  /**
   * Caps and deprioritises any skill whose signal would otherwise escalate past FOUNDATIONAL while
   * at least one of its prerequisites has not reached MASTERED. Every other signal passes through
   * unchanged, including the case where a skill has prerequisites that ARE all secured.
   *
   * @param baseSignals every skill's signal as {@link AdaptiveDiagnosticSelector} would already
   *     receive it under V2 -- unmodified by this method for any skill this adjustment does not
   *     apply to
   * @param prerequisiteSkillCodesBySkillCode each skill's direct prerequisites, from
   *     {@code CurriculumGraph.SkillNode#prerequisiteSkillCodes()}; a skill absent from this map or
   *     mapped to an empty list is treated as having none
   * @param statusBySkillCode each skill's latest {@link MasteryStatus}, where known. A prerequisite
   *     absent from this map -- unknown, not merely unmastered -- is treated as not secured, the
   *     same conservative default {@code GapDiagnosisService} uses: escalating on an unproven
   *     foundation is not safer than escalating on a confirmed weak one.
   */
  public static Map<String, SkillMasterySignal> adjustForPrerequisites(
      Map<String, SkillMasterySignal> baseSignals,
      Map<String, List<String>> prerequisiteSkillCodesBySkillCode,
      Map<String, MasteryStatus> statusBySkillCode) {
    Map<String, SkillMasterySignal> adjusted = new LinkedHashMap<>(baseSignals.size());
    for (Map.Entry<String, SkillMasterySignal> entry : baseSignals.entrySet()) {
      adjusted.put(entry.getKey(), adjust(
          entry.getKey(), entry.getValue(), prerequisiteSkillCodesBySkillCode, statusBySkillCode));
    }
    return adjusted;
  }

  private static SkillMasterySignal adjust(
      String skillCode, SkillMasterySignal base,
      Map<String, List<String>> prerequisiteSkillCodesBySkillCode,
      Map<String, MasteryStatus> statusBySkillCode) {
    if (base.targetDifficulty() == AssessmentDifficulty.FOUNDATIONAL) {
      return base;
    }
    List<String> prerequisites =
        prerequisiteSkillCodesBySkillCode.getOrDefault(skillCode, List.of());
    if (prerequisites.isEmpty()) {
      return base;
    }
    boolean everyPrerequisiteSecured = prerequisites.stream()
        .allMatch(code -> statusBySkillCode.get(code) == MasteryStatus.MASTERED);
    if (everyPrerequisiteSecured) {
      return base;
    }
    return new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.PREREQUISITE_NOT_SECURED, DEMOTED_PRIORITY);
  }
}
