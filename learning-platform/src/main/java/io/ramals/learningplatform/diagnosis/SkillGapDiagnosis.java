package io.ramals.learningplatform.diagnosis;

import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.List;
import java.util.Set;

/**
 * One skill's gap diagnosis: what the learner's own evidence says about it, and -- when it reads
 * weak -- what the prerequisite graph says about whether that weakness is likely inherited.
 *
 * @param ownStatus the skill's own latest {@link MasteryStatus}, or {@code null} if no mastery
 *     snapshot exists for this learner and skill at all. {@code null} and
 *     {@link MasteryStatus#INSUFFICIENT_EVIDENCE} both classify as
 *     {@link GapClassification#INSUFFICIENT_EVIDENCE} -- operationally the same thing, not enough
 *     signal to diagnose -- but are kept distinguishable here for a caller that cares which.
 * @param weakPrerequisiteSkillCodes this skill's direct prerequisites (one hop) whose own status is
 *     also a real weakness. Empty when the skill has no prerequisites, when none of them are weak,
 *     or when {@code classification} is not one driven by prerequisite state.
 * @param candidateRootCauseSkillCodes the most-upstream weak skill(s) reachable by walking only
 *     weak prerequisite edges from this skill. Non-empty only for
 *     {@link GapClassification#PREREQUISITE_GAP} and {@link GapClassification#POSSIBLY_INHERITED_GAP}.
 *     More than one entry means the weakness has more than one plausible upstream explanation, not
 *     that this class failed to pick one -- it does not force a single answer where the graph
 *     doesn't support one.
 */
public record SkillGapDiagnosis(
    String skillCode,
    GapClassification classification,
    MasteryStatus ownStatus,
    List<String> weakPrerequisiteSkillCodes,
    Set<String> candidateRootCauseSkillCodes) {
}
