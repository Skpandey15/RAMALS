package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * DIAGNOSTIC_SELECTION_V4: composes with V3 (which already composes with V2) the same way V3
 * composed with V2 -- one more adjustment step applied to the signal map before it reaches
 * {@link AdaptiveDiagnosticSelector#select}, everything upstream of it left completely unmodified.
 *
 * <p><b>What "hypothesis confirmation" means here, precisely.</b> Not a live follow-up question
 * within the same attempt -- {@code DiagnosticSubmissionService} takes a whole packet's worth of
 * responses at once and completes the attempt, and there is no turn-based "ask one more because
 * that answer was a surprise" path today. What this class does instead: when a learner's own
 * mastery history shows their most recent snapshot for a skill is a *worse* status than the one
 * before it -- an unexpected regression -- that skill is prioritised for confirmation in their
 * NEXT diagnostic attempt. The follow-up is deferred to the next session, not immediate; that is
 * the accepted cost of reusing the existing one-shot-per-attempt model rather than redesigning it.
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

  private HypothesisConfirmationDiagnosticSelector() {
  }

  /**
   * A learner's two most recent snapshots for one skill, compared: {@code previous} is a real,
   * evidenced status (not {@link MasteryStatus#INSUFFICIENT_EVIDENCE}) and {@code latest} is
   * strictly worse than it by the enum's own declared order (INSUFFICIENT_EVIDENCE, NEEDS_RETEACH,
   * NEEDS_PRACTICE, DEVELOPING, MASTERED -- ascending). {@code previous} being
   * INSUFFICIENT_EVIDENCE is excluded deliberately: going from "unknown" to "known and weak" is
   * evidence arriving for the first time, not a regression from anything.
   */
  public static boolean isRegression(MasteryStatus previous, MasteryStatus latest) {
    if (previous == null || latest == null || previous == MasteryStatus.INSUFFICIENT_EVIDENCE) {
      return false;
    }
    return previous.ordinal() > latest.ordinal();
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
