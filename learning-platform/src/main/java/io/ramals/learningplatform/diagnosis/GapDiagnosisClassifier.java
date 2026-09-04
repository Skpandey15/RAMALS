package io.ramals.learningplatform.diagnosis;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumGraph.SkillNode;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure classification logic behind {@link GapDiagnosisService}: given a curriculum graph and a
 * learner's already-computed mastery status per skill, decides what each skill's weakness (if any)
 * is best explained by. Kept separate from the service so it can be tested from a seed with no
 * database, the same split {@code AdaptiveDiagnosticSelector} keeps from {@code DiagnosticService}.
 *
 * <p>Reads nothing and writes nothing; a call with the same graph and the same status map always
 * produces the same report.
 */
final class GapDiagnosisClassifier {

  private GapDiagnosisClassifier() {
  }

  static List<SkillGapDiagnosis> classifyAll(
      CurriculumGraph graph, Map<String, MasteryStatus> statusByCode) {
    Map<String, SkillNode> nodesByCode = new LinkedHashMap<>();
    for (SkillNode node : graph.skills()) {
      nodesByCode.put(node.stableCode(), node);
    }

    List<SkillGapDiagnosis> diagnoses = new ArrayList<>(graph.skills().size());
    for (SkillNode node : graph.skills()) {
      diagnoses.add(classify(node.stableCode(), nodesByCode, statusByCode));
    }
    return List.copyOf(diagnoses);
  }

  private static SkillGapDiagnosis classify(
      String skillCode, Map<String, SkillNode> nodesByCode, Map<String, MasteryStatus> statusByCode) {
    MasteryStatus own = statusByCode.get(skillCode);

    if (own == null || own == MasteryStatus.INSUFFICIENT_EVIDENCE) {
      return new SkillGapDiagnosis(
          skillCode, GapClassification.INSUFFICIENT_EVIDENCE, own, List.of(), Set.of());
    }
    if (own == MasteryStatus.MASTERED) {
      return new SkillGapDiagnosis(
          skillCode, GapClassification.CONFIRMED_STRENGTH, own, List.of(), Set.of());
    }

    // A real, evidenced weakness: NEEDS_RETEACH, NEEDS_PRACTICE, or DEVELOPING.
    List<String> prerequisites = nodesByCode.get(skillCode).prerequisiteSkillCodes();
    List<String> weakPrerequisites = prerequisites.stream()
        .filter(code -> isWeak(statusByCode.get(code)))
        .toList();

    if (prerequisites.isEmpty() || weakPrerequisites.isEmpty()) {
      // No prerequisites, or none of them are weak. Given own is a weakness, a non-weak
      // prerequisite present in the status map is MASTERED; one absent from it (or itself
      // INSUFFICIENT_EVIDENCE) is unknown rather than confirmed secure.
      boolean anyUnknown = prerequisites.stream().anyMatch(code -> isUnknown(statusByCode.get(code)));
      GapClassification classification =
          anyUnknown ? GapClassification.POSSIBLY_INHERITED_GAP : GapClassification.INDEPENDENT_GAP;
      return new SkillGapDiagnosis(skillCode, classification, own, List.of(), Set.of());
    }

    boolean allWeak = weakPrerequisites.size() == prerequisites.size();
    Set<String> rootCauses = new LinkedHashSet<>();
    for (String weakPrerequisite : weakPrerequisites) {
      rootCauses.addAll(
          findRootCauses(weakPrerequisite, nodesByCode, statusByCode, new LinkedHashSet<>()));
    }

    GapClassification classification =
        allWeak ? GapClassification.PREREQUISITE_GAP : GapClassification.POSSIBLY_INHERITED_GAP;
    return new SkillGapDiagnosis(skillCode, classification, own, weakPrerequisites, rootCauses);
  }

  /**
   * Walks weak prerequisite edges upward from {@code skillCode} to find the most-upstream weak
   * skill(s) -- the candidate root cause(s) -- rather than stopping at the immediate parent.
   *
   * <p>{@code visiting} tracks the current path, not the whole walk, and backtracks: a skill is
   * removed once its own subtree is fully explored, not left marked forever. A DAG can legitimately
   * re-converge -- {@code KAFKA_FAILURE_RECOVERY} has two prerequisites that both trace back through
   * different paths to {@code KAFKA_PARTITION} -- and that is a diamond, not a cycle; leaving a
   * skill marked after its own branch finishes would misattribute the second branch's genuine
   * re-convergence as a false self-referential root. A skill still active on the current call
   * stack -- a genuine cycle, which {@code CurriculumGraphValidator} should make unreachable on
   * publish but this does not trust that alone -- is still caught, because it is only ever removed
   * after its own subtree finishes, and the cyclic edge is still inside that subtree.
   */
  private static Set<String> findRootCauses(
      String skillCode, Map<String, SkillNode> nodesByCode, Map<String, MasteryStatus> statusByCode,
      Set<String> visiting) {
    if (!visiting.add(skillCode)) {
      return Set.of(skillCode);
    }
    try {
      List<String> weakPrerequisites = nodesByCode.get(skillCode).prerequisiteSkillCodes().stream()
          .filter(code -> isWeak(statusByCode.get(code)))
          .toList();
      if (weakPrerequisites.isEmpty()) {
        return Set.of(skillCode);
      }
      Set<String> roots = new LinkedHashSet<>();
      for (String weakPrerequisite : weakPrerequisites) {
        roots.addAll(findRootCauses(weakPrerequisite, nodesByCode, statusByCode, visiting));
      }
      return roots;
    } finally {
      visiting.remove(skillCode);
    }
  }

  private static boolean isWeak(MasteryStatus status) {
    return status == MasteryStatus.NEEDS_RETEACH
        || status == MasteryStatus.NEEDS_PRACTICE
        || status == MasteryStatus.DEVELOPING;
  }

  private static boolean isUnknown(MasteryStatus status) {
    return status == null || status == MasteryStatus.INSUFFICIENT_EVIDENCE;
  }
}
