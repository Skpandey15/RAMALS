package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.ai.DiagnosticProposalGate.Decision;
import io.ramals.learningplatform.ai.DiagnosticProposalGate.Proposal;
import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deterministic answer to an agent's proposal.
 *
 * <p>An agent that proposes probing a skill out of order is not misbehaving — it is being useful and
 * wrong, because skipping ahead to the obvious gap is exactly what a helpful tutor would suggest.
 * The platform's job is to say no cheaply, every time, and to leave a trace that it happened.
 *
 * <p>These tests are written from the harm backwards: what could a plausible, well-intentioned
 * proposal do to a learner, and does the gate stop it?
 */
class DiagnosticProposalGateTests {

  private SimpleMeterRegistry registry;
  private DiagnosticProposalGate gate;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    gate = new DiagnosticProposalGate(registry);
  }

  private double decisionCount(Decision decision) {
    return registry.counter("ramals.ai.diagnostic.gate", "decision", decision.tag()).count();
  }

  /** TOPIC has no prerequisites; PARTITIONING requires TOPIC. */
  private static CurriculumGraph curriculum() {
    return new CurriculumGraph(
        UUID.randomUUID(), "KAFKA", "v1", "PUBLISHED",
        List.of(
            skill("KAFKA_TOPIC", List.of(), List.of("TOPIC_DEFINE", "TOPIC_PARTITION_COUNT")),
            skill("KAFKA_PARTITIONING", List.of("KAFKA_TOPIC"), List.of("PARTITION_ORDERING"))));
  }

  private static CurriculumGraph.SkillNode skill(
      String code, List<String> prerequisites, List<String> objectiveCodes) {
    return new CurriculumGraph.SkillNode(
        UUID.randomUUID(), code, code, "", "FOUNDATIONAL",
        new BigDecimal("0.8000"), 30, new BigDecimal("0.8000"), new BigDecimal("0.7000"),
        3, List.of("QUIZ"), List.of("FOUNDATIONAL"), 1,
        objectiveCodes.stream()
            .map(objective -> new CurriculumGraph.Objective(objective, "", true, 1))
            .toList(),
        prerequisites);
  }

  private static Proposal probe(String skillCode, String objectiveCode) {
    return new Proposal(skillCode, objectiveCode, "FOUNDATIONAL", "next gap", null);
  }

  // -- prerequisite policy cannot be bypassed ---------------------------------------------------

  @Test
  @DisplayName("a probe whose prerequisite is unmastered is refused")
  void prerequisiteNotMasteredIsRefused() {
    Decision decision = gate.evaluate(
        probe("KAFKA_PARTITIONING", "PARTITION_ORDERING"),
        curriculum(),
        Map.of("KAFKA_TOPIC", MasteryStatus.NEEDS_PRACTICE));

    assertThat(decision).isEqualTo(Decision.PREREQUISITE_NOT_MET);
  }

  @Test
  @DisplayName("a probe whose prerequisite has no evidence at all is refused")
  void prerequisiteWithNoEvidenceIsRefused() {
    // Absent from the map is weaker than INSUFFICIENT_EVIDENCE, not stronger. Treating "never
    // measured" as satisfying a prerequisite would let a learner skip the whole chain.
    Decision decision = gate.evaluate(
        probe("KAFKA_PARTITIONING", "PARTITION_ORDERING"), curriculum(), Map.of());

    assertThat(decision).isEqualTo(Decision.PREREQUISITE_NOT_MET);
  }

  @Test
  @DisplayName("a probe whose prerequisite is mastered is accepted")
  void masteredPrerequisiteIsAccepted() {
    Decision decision = gate.evaluate(
        probe("KAFKA_PARTITIONING", "PARTITION_ORDERING"),
        curriculum(),
        Map.of("KAFKA_TOPIC", MasteryStatus.MASTERED));

    assertThat(decision).isEqualTo(Decision.ACCEPTED);
  }

  @Test
  @DisplayName("a skill with no prerequisites is accepted from a standing start")
  void rootSkillIsAccepted() {
    assertThat(gate.evaluate(probe("KAFKA_TOPIC", "TOPIC_DEFINE"), curriculum(), Map.of()))
        .isEqualTo(Decision.ACCEPTED);
  }

  // -- invalid objective and skill ----------------------------------------------------------------

  @Test
  @DisplayName("a skill outside this curriculum version is refused")
  void unknownSkillIsRefused() {
    assertThat(gate.evaluate(probe("KAFKA_STREAMS", null), curriculum(), Map.of()))
        .isEqualTo(Decision.UNKNOWN_SKILL);
  }

  @Test
  @DisplayName("an objective belonging to a different skill is refused")
  void objectiveFromAnotherSkillIsRefused() {
    // Plausible and wrong: the objective is real, so nothing about it looks invented. Acting on it
    // would probe the learner on one thing and file the evidence under another.
    Decision decision = gate.evaluate(
        probe("KAFKA_TOPIC", "PARTITION_ORDERING"), curriculum(), Map.of());

    assertThat(decision).isEqualTo(Decision.OBJECTIVE_NOT_IN_SKILL);
  }

  @Test
  @DisplayName("an invented objective is refused")
  void inventedObjectiveIsRefused() {
    assertThat(gate.evaluate(probe("KAFKA_TOPIC", "TOPIC_VIBES"), curriculum(), Map.of()))
        .isEqualTo(Decision.OBJECTIVE_NOT_IN_SKILL);
  }

  // -- sparse evidence never becomes a verdict ----------------------------------------------------

  @Test
  @DisplayName("insufficient evidence must not be reported as mastery")
  void sparseEvidenceCannotBecomeMastery() {
    Proposal proposal = new Proposal(
        "KAFKA_TOPIC", "TOPIC_DEFINE", "FOUNDATIONAL",
        "they seem to have this", "MASTERED");

    Decision decision = gate.evaluate(
        proposal, curriculum(), Map.of("KAFKA_TOPIC", MasteryStatus.INSUFFICIENT_EVIDENCE));

    assertThat(decision).isEqualTo(Decision.INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE);
  }

  @Test
  @DisplayName("insufficient evidence must not be reported as failure")
  void sparseEvidenceCannotBecomeFailure() {
    // The more likely direction, and the more damaging one: two wrong answers is not a finding
    // about a learner, and recording it as one is a claim the platform has not earned.
    Proposal proposal = new Proposal(
        "KAFKA_TOPIC", "TOPIC_DEFINE", "FOUNDATIONAL",
        "they are struggling", "NEEDS_PRACTICE");

    Decision decision = gate.evaluate(
        proposal, curriculum(), Map.of("KAFKA_TOPIC", MasteryStatus.INSUFFICIENT_EVIDENCE));

    assertThat(decision).isEqualTo(Decision.INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE);
  }

  @Test
  @DisplayName("a skill never measured is treated the same as insufficient evidence")
  void neverMeasuredIsAlsoSparse() {
    Proposal proposal = new Proposal(
        "KAFKA_TOPIC", "TOPIC_DEFINE", "FOUNDATIONAL", "guessing", "NEEDS_PRACTICE");

    assertThat(gate.evaluate(proposal, curriculum(), Map.of()))
        .isEqualTo(Decision.INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE);
  }

  @Test
  @DisplayName("restating insufficient evidence is allowed")
  void restatingSparsenessIsAllowed() {
    // The agent may say "we do not know yet" -- that is the only honest inference available, and
    // refusing it would leave the agent unable to explain why it wants another probe.
    Proposal proposal = new Proposal(
        "KAFKA_TOPIC", "TOPIC_DEFINE", "FOUNDATIONAL",
        "not enough evidence yet", "INSUFFICIENT_EVIDENCE");

    assertThat(gate.evaluate(
        proposal, curriculum(), Map.of("KAFKA_TOPIC", MasteryStatus.INSUFFICIENT_EVIDENCE)))
        .isEqualTo(Decision.ACCEPTED);
  }

  @Test
  @DisplayName("a status the platform actually recorded may be restated")
  void recordedStatusMayBeRestated() {
    // With real evidence behind it, reporting NEEDS_PRACTICE is repeating the platform's own
    // finding rather than inventing one.
    Proposal proposal = new Proposal(
        "KAFKA_TOPIC", "TOPIC_DEFINE", "FOUNDATIONAL", "known gap", "NEEDS_PRACTICE");

    assertThat(gate.evaluate(
        proposal, curriculum(), Map.of("KAFKA_TOPIC", MasteryStatus.NEEDS_PRACTICE)))
        .isEqualTo(Decision.ACCEPTED);
  }

  // -- disagreements are visible -------------------------------------------------------------------

  @Test
  @DisplayName("every decision increments a counter tagged with its outcome")
  void decisionsAreCounted() {
    gate.evaluate(probe("KAFKA_TOPIC", "TOPIC_DEFINE"), curriculum(), Map.of());
    gate.evaluate(probe("KAFKA_STREAMS", null), curriculum(), Map.of());
    gate.evaluate(
        probe("KAFKA_PARTITIONING", "PARTITION_ORDERING"), curriculum(), Map.of());

    // A gate that silently discarded proposals would hide the one signal that says the context is
    // too thin or the prompt has drifted.
    assertThat(decisionCount(Decision.ACCEPTED)).isEqualTo(1);
    assertThat(decisionCount(Decision.UNKNOWN_SKILL)).isEqualTo(1);
    assertThat(decisionCount(Decision.PREREQUISITE_NOT_MET)).isEqualTo(1);
  }

  @Test
  @DisplayName("every refusal reason is distinguishable in metrics")
  void everyReasonHasItsOwnTag() {
    List<String> tags = java.util.Arrays.stream(Decision.values()).map(Decision::tag).toList();

    assertThat(tags).doesNotHaveDuplicates();
    assertThat(tags).hasSize(Decision.values().length);
  }

  @Test
  @DisplayName("prerequisite order is checked before any inference claim")
  void prerequisiteIsCheckedFirst() {
    // A proposal that is wrong twice should report the more serious refusal. Prerequisite order
    // protects the learner's path; an inference claim only misstates their state.
    Proposal doublyWrong = new Proposal(
        "KAFKA_PARTITIONING", "PARTITION_ORDERING", "FOUNDATIONAL", "skip ahead", "MASTERED");

    assertThat(gate.evaluate(doublyWrong, curriculum(), Map.of()))
        .isEqualTo(Decision.PREREQUISITE_NOT_MET);
  }
}
