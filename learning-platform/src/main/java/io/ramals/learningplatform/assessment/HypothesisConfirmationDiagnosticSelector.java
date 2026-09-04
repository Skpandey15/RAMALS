package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * DIAGNOSTIC_SELECTION_V4 ("H4a": cross-attempt regression confirmation). Composes with V3 (which
 * already composes with V2) the same way V3 composed with V2 -- one more adjustment step applied
 * to the signal map before it reaches {@link AdaptiveDiagnosticSelector#select}, everything
 * upstream of it left completely unmodified.
 *
 * <p><b>Scope: this is H4a, not the full roadmap "hypothesis confirmation" idea.</b> The roadmap's
 * larger notion -- an unexpected answer causing a deterministic, related/root-cause probe to be
 * selected -- is H4b, and is deliberately not implemented here. What this class actually does is
 * narrower and fully specified: when a learner's own mastery history shows their most recent
 * snapshot for a skill is a *worse* status than the one before it, by V4's own frozen rank (see
 * {@link #MASTERY_RANK}), that same skill is reprioritised for confirmation in their NEXT
 * diagnostic attempt. There is no probe of a *different*, causally related skill here -- H4b, if
 * built, is a separate, later capability with its own version identifier.
 *
 * <p><b>Not a live follow-up question within the same attempt.</b> {@code
 * DiagnosticSubmissionService} takes a whole packet's worth of responses at once and completes the
 * attempt, and there is no turn-based "ask one more because that answer was a surprise" path today.
 * The follow-up is deferred to the next session, not immediate; that is the accepted cost of reusing
 * the existing one-shot-per-attempt model rather than redesigning it.
 *
 * <p><b>Never changes the band, only the reason and the priority.</b> {@link #adjustForRegressions}
 * takes signals that have already passed through V2's own computation and, where V3 applies,
 * {@link PrerequisiteAwareDiagnosticSelector}'s cap. A regressed skill still gets exactly the band
 * that upstream decision chain already settled on -- capped by an unsecured prerequisite if one
 * exists, held by the skill's own weak evidence otherwise. What changes is that this skill now
 * gets visited first, with a reason that says why: not "routine low confidence" but "this
 * specifically contradicts what was believed a moment ago."
 *
 * <p><b>Detecting a regression is not this class's job.</b> {@link #isRegression} is the pure rule;
 * walking a learner's actual snapshot history to find out where it applies belongs to
 * {@code DiagnosticService}, the same separation {@link PrerequisiteAwareDiagnosticSelector} keeps
 * from the curriculum-graph lookups that feed it.
 */
public final class HypothesisConfirmationDiagnosticSelector {

  public static final String SELECTION_POLICY_VERSION = "DIAGNOSTIC_SELECTION_V4";

  /** Tied with UNSEEN_ITEM: a genuine regression from previously-stronger evidence is at least as
   * urgent as a skill that has never been tested at all. */
  private static final int PRIORITY_HYPOTHESIS_CONFIRMATION = 0;

  /**
   * V4's own frozen mastery-rank mapping -- deliberately independent of
   * {@link MasteryStatus#ordinal()}. Enum declaration order is a Java implementation detail, free
   * to change (a status inserted, reordered, or renamed for reasons that have nothing to do with
   * V4) without that silently redefining what "worse" means to this selector. This map is the one
   * place V4 pins that meaning down, and changing it is changing V4's domain semantics -- subject
   * to the same freeze discipline as everything else this class does (mint a V-next rather than
   * edit it in place once a real assessment version is running under V4).
   *
   * <p>{@link MasteryStatus#INSUFFICIENT_EVIDENCE} is deliberately absent: it is not a rank on this
   * scale, it is "no evidence yet". {@link #isRegression} handles it as an explicit special case
   * rather than giving it a rank that would make some other transition compare against it.
   */
  private static final Map<MasteryStatus, Integer> MASTERY_RANK = Map.of(
      MasteryStatus.NEEDS_RETEACH, 0,
      MasteryStatus.NEEDS_PRACTICE, 1,
      MasteryStatus.DEVELOPING, 2,
      MasteryStatus.MASTERED, 3);

  private HypothesisConfirmationDiagnosticSelector() {
  }

  /**
   * A learner's two most recent snapshots for one skill, compared by V4's own frozen
   * {@link #MASTERY_RANK}: {@code previous} is a real, evidenced status (not
   * {@link MasteryStatus#INSUFFICIENT_EVIDENCE}) and {@code latest} ranks strictly below it.
   * {@code previous} being INSUFFICIENT_EVIDENCE is excluded deliberately: going from "unknown" to
   * "known and weak" is evidence arriving for the first time, not a regression from anything. A
   * status absent from {@link #MASTERY_RANK} (only INSUFFICIENT_EVIDENCE today) on either side is
   * treated the same way: not comparable, so never a regression.
   */
  public static boolean isRegression(MasteryStatus previous, MasteryStatus latest) {
    if (previous == null || latest == null || previous == MasteryStatus.INSUFFICIENT_EVIDENCE) {
      return false;
    }
    Integer previousRank = MASTERY_RANK.get(previous);
    Integer latestRank = MASTERY_RANK.get(latest);
    if (previousRank == null || latestRank == null) {
      return false;
    }
    return previousRank > latestRank;
  }

  /**
   * Overrides the reason and priority of every signal whose skill code is in
   * {@code regressedSkillCodes}, leaving its {@code targetDifficulty} exactly as upstream (V2, then
   * V3 if applied) already decided it. A skill not in the set passes through completely unchanged.
   */
  public static Map<String, SkillMasterySignal> adjustForRegressions(
      Map<String, SkillMasterySignal> baseSignals, Set<String> regressedSkillCodes) {
    if (regressedSkillCodes.isEmpty()) {
      return baseSignals;
    }
    Map<String, SkillMasterySignal> adjusted = new LinkedHashMap<>(baseSignals.size());
    for (Map.Entry<String, SkillMasterySignal> entry : baseSignals.entrySet()) {
      SkillMasterySignal base = entry.getValue();
      adjusted.put(entry.getKey(), regressedSkillCodes.contains(entry.getKey())
          ? new SkillMasterySignal(
              base.targetDifficulty(), SelectionReason.HYPOTHESIS_CONFIRMATION,
              PRIORITY_HYPOTHESIS_CONFIRMATION)
          : base);
    }
    return adjusted;
  }
}
