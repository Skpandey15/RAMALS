package io.ramals.learningplatform.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumGraph.SkillNode;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pure classification logic against synthetic graphs. No database, no Spring -- a call with the
 * same graph and status map always produces the same report.
 */
class GapDiagnosisClassifierTests {

  @Test
  void noSnapshotAtAllIsInsufficientEvidence() {
    CurriculumGraph graph = graph(skill("A"));

    List<SkillGapDiagnosis> result = GapDiagnosisClassifier.classifyAll(graph, Map.of());

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().classification()).isEqualTo(GapClassification.INSUFFICIENT_EVIDENCE);
    assertThat(result.getFirst().ownStatus()).isNull();
  }

  @Test
  void masteredIsConfirmedStrengthRegardlessOfPrerequisites() {
    CurriculumGraph graph = graph(skill("A"), skill("B", "A"));
    Map<String, MasteryStatus> statuses = Map.of(
        "A", MasteryStatus.NEEDS_RETEACH, // even a weak prerequisite doesn't change this
        "B", MasteryStatus.MASTERED);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "B");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.CONFIRMED_STRENGTH);
  }

  @Test
  void weakSkillWithNoPrerequisitesIsIndependentGap() {
    CurriculumGraph graph = graph(skill("A"));
    Map<String, MasteryStatus> statuses = Map.of("A", MasteryStatus.NEEDS_PRACTICE);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "A");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.INDEPENDENT_GAP);
    assertThat(diagnosis.candidateRootCauseSkillCodes()).isEmpty();
  }

  @Test
  void weakSkillWithAMasteredPrerequisiteIsIndependentGap() {
    CurriculumGraph graph = graph(skill("A"), skill("B", "A"));
    Map<String, MasteryStatus> statuses = Map.of(
        "A", MasteryStatus.MASTERED,
        "B", MasteryStatus.DEVELOPING);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "B");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.INDEPENDENT_GAP);
  }

  @Test
  void weakSkillWithAWeakPrerequisiteThatHasNoFurtherPrerequisitesIsPrerequisiteGap() {
    CurriculumGraph graph = graph(skill("A"), skill("B", "A"));
    Map<String, MasteryStatus> statuses = Map.of(
        "A", MasteryStatus.NEEDS_RETEACH,
        "B", MasteryStatus.DEVELOPING);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "B");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.PREREQUISITE_GAP);
    assertThat(diagnosis.weakPrerequisiteSkillCodes()).containsExactly("A");
    assertThat(diagnosis.candidateRootCauseSkillCodes()).containsExactly("A");
  }

  @Test
  void weakSkillWithAnUnprovenPrerequisiteIsPossiblyInherited() {
    CurriculumGraph graph = graph(skill("A"), skill("B", "A"));
    Map<String, MasteryStatus> statuses = Map.of("B", MasteryStatus.DEVELOPING); // A: no snapshot

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "B");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.POSSIBLY_INHERITED_GAP);
  }

  @Test
  void aThreeSkillChainNamesTheRootNotJustTheImmediateParent() {
    // A (root) -> B -> C, all weak. C's diagnosis must name A, not merely B.
    CurriculumGraph graph = graph(skill("A"), skill("B", "A"), skill("C", "B"));
    Map<String, MasteryStatus> statuses = Map.of(
        "A", MasteryStatus.NEEDS_RETEACH,
        "B", MasteryStatus.NEEDS_PRACTICE,
        "C", MasteryStatus.DEVELOPING);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "C");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.PREREQUISITE_GAP);
    assertThat(diagnosis.weakPrerequisiteSkillCodes()).containsExactly("B");
    assertThat(diagnosis.candidateRootCauseSkillCodes()).containsExactly("A");
  }

  @Test
  void twoPrerequisitesOneWeakOneMasteredIsPossiblyInheritedNotPrerequisiteGap() {
    CurriculumGraph graph = graph(skill("A"), skill("B"), skill("C", "A", "B"));
    Map<String, MasteryStatus> statuses = Map.of(
        "A", MasteryStatus.NEEDS_RETEACH,
        "B", MasteryStatus.MASTERED,
        "C", MasteryStatus.DEVELOPING);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "C");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.POSSIBLY_INHERITED_GAP);
    assertThat(diagnosis.weakPrerequisiteSkillCodes()).containsExactly("A");
  }

  @Test
  void twoPrerequisitesBothWeakLeadingToDifferentAncestorsReportsBothRoots() {
    // A (root) -> C; D (root) -> C (as prerequisites), both weak, distinct upstream roots.
    CurriculumGraph graph = graph(skill("A"), skill("D"), skill("C", "A", "D"));
    Map<String, MasteryStatus> statuses = Map.of(
        "A", MasteryStatus.NEEDS_RETEACH,
        "D", MasteryStatus.NEEDS_PRACTICE,
        "C", MasteryStatus.DEVELOPING);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "C");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.PREREQUISITE_GAP);
    assertThat(diagnosis.candidateRootCauseSkillCodes()).containsExactlyInAnyOrder("A", "D");
  }

  @Test
  void aDiamondThatReconvergesOnOneRootIsNotMisreadAsACycle() {
    // G (root, weak) -> P1 (weak) -> S
    //                 -> P2 (weak) -> S
    // Both P1 and P2 share the same upstream weak ancestor G. This is the shape
    // KAFKA_FAILURE_RECOVERY actually has in the seeded curriculum (two prerequisites that both
    // trace back through different paths to KAFKA_PARTITION), and is the regression case for a
    // shared mutable visited-set incorrectly treating a legitimate re-convergence as a cycle.
    CurriculumGraph graph = graph(
        skill("G"), skill("P1", "G"), skill("P2", "G"), skill("S", "P1", "P2"));
    Map<String, MasteryStatus> statuses = Map.of(
        "G", MasteryStatus.NEEDS_RETEACH,
        "P1", MasteryStatus.NEEDS_PRACTICE,
        "P2", MasteryStatus.DEVELOPING,
        "S", MasteryStatus.DEVELOPING);

    SkillGapDiagnosis diagnosis = diagnosisOf(graph, statuses, "S");

    assertThat(diagnosis.classification()).isEqualTo(GapClassification.PREREQUISITE_GAP);
    // Deduplicated to the single true root, not {G, G} and not a false self-reference from either
    // P1 or P2 caused by treating the other branch's visit to G as if it were still on this path.
    assertThat(diagnosis.candidateRootCauseSkillCodes()).containsExactly("G");
  }

  @Test
  void classifyAllPreservesGraphOrderAndCoversEverySkill() {
    CurriculumGraph graph = graph(skill("A"), skill("B", "A"), skill("C"));
    Map<String, MasteryStatus> statuses = Map.of(
        "A", MasteryStatus.MASTERED, "B", MasteryStatus.DEVELOPING);

    List<SkillGapDiagnosis> result = GapDiagnosisClassifier.classifyAll(graph, statuses);

    assertThat(result).extracting(SkillGapDiagnosis::skillCode).containsExactly("A", "B", "C");
  }

  private static SkillGapDiagnosis diagnosisOf(
      CurriculumGraph graph, Map<String, MasteryStatus> statuses, String skillCode) {
    return GapDiagnosisClassifier.classifyAll(graph, statuses).stream()
        .filter(d -> d.skillCode().equals(skillCode))
        .findFirst().orElseThrow();
  }

  private static CurriculumGraph graph(SkillNode... skills) {
    return new CurriculumGraph(
        UUID.randomUUID(), "TEST", "v1", "PUBLISHED", List.of(skills));
  }

  private static SkillNode skill(String code, String... prerequisites) {
    return new SkillNode(
        UUID.randomUUID(), code, code, code, "FOUNDATIONAL",
        new BigDecimal("0.80"), 10, new BigDecimal("0.75"), new BigDecimal("0.75"), 4,
        List.of(), List.of("EASY"), 1, List.of(), List.of(prerequisites));
  }
}
