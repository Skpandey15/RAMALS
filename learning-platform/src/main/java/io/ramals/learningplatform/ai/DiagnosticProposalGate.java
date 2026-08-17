package io.ramals.learningplatform.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a diagnostic proposal may be acted on.
 *
 * <p>The agent proposes which objective to probe next. It does not get to decide. Everything that
 * could actually harm a learner — probing a skill whose prerequisites are unmet, or treating "we do
 * not know yet" as "they failed" — is refused here, against the curriculum graph and the recorded
 * mastery state, by code a model never sees.
 *
 * <p>That separation is the whole design. A prompt asking an agent to respect prerequisites is a
 * request; this is the answer. An agent that proposes something out of order is not misbehaving, it
 * is being useful and wrong, and the platform's job is to say no cheaply and record that it happened.
 *
 * <p>Disagreements are counted rather than suppressed. A gate that silently discards a large
 * fraction of proposals is telling you something — that the context is too thin, the prompt is
 * wrong, or the curriculum has moved — and none of that is visible if refusals leave no trace.
 */
@Component
public class DiagnosticProposalGate {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticProposalGate.class);

  private static final String DECISION_METRIC = "ramals.ai.diagnostic.gate";

  private final MeterRegistry meterRegistry;

  public DiagnosticProposalGate(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** Why a proposal was refused, or that it was accepted. */
  public enum Decision {
    ACCEPTED("accepted"),
    /** The named skill is not in this curriculum version. */
    UNKNOWN_SKILL("unknown_skill"),
    /** The objective is real but belongs to a different skill. */
    OBJECTIVE_NOT_IN_SKILL("objective_not_in_skill"),
    /** A prerequisite of the proposed skill is not yet mastered. */
    PREREQUISITE_NOT_MET("prerequisite_not_met"),
    /** The proposal read a sparse-evidence state as a verdict. */
    INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE("inferred_verdict_from_insufficient_evidence");

    private final String tag;

    Decision(String tag) {
      this.tag = tag;
    }

    public String tag() {
      return tag;
    }

    public boolean accepted() {
      return this == ACCEPTED;
    }
  }

  /**
   * A diagnostic agent's proposal, reduced to the parts the gate must check.
   *
   * @param inferredStatus what the agent believes the learner's state is, when it says so at all.
   *     Present only so the gate can refuse it; it is never adopted.
   */
  public record Proposal(
      String skillCode, String objectiveCode, String difficulty, String rationale,
      String inferredStatus) {
  }

  /**
   * Evaluates a proposal against the curriculum and the recorded mastery state.
   *
   * @param masteryByskill recorded status per skill. A skill absent from this map has no evidence
   *     at all, which is a weaker state than {@code INSUFFICIENT_EVIDENCE}, not a stronger one.
   */
  public Decision evaluate(
      Proposal proposal, CurriculumGraph curriculum, Map<String, MasteryStatus> masteryByskill) {

    Decision decision = decide(proposal, curriculum, masteryByskill);
    record(proposal, decision);
    return decision;
  }

  private Decision decide(
      Proposal proposal, CurriculumGraph curriculum, Map<String, MasteryStatus> masteryByskill) {

    Optional<CurriculumGraph.SkillNode> skill = findSkill(curriculum, proposal.skillCode());
    if (skill.isEmpty()) {
      return Decision.UNKNOWN_SKILL;
    }

    // An objective from another skill would send the learner a probe about something they were not
    // being assessed on, and would attribute the resulting evidence to the wrong skill.
    if (proposal.objectiveCode() != null
        && !objectiveBelongsToSkill(skill.get(), proposal.objectiveCode())) {
      return Decision.OBJECTIVE_NOT_IN_SKILL;
    }

    // The criterion that matters most. An agent may reasonably propose probing a skill the learner
    // is not ready for -- it looks like the obvious next gap. Prerequisite order is a curriculum
    // decision, and no proposal may route around it.
    for (String prerequisite : skill.get().prerequisiteSkillCodes()) {
      if (masteryByskill.get(prerequisite) != MasteryStatus.MASTERED) {
        return Decision.PREREQUISITE_NOT_MET;
      }
    }

    if (inferredAVerdictFromSparseEvidence(proposal, masteryByskill)) {
      return Decision.INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE;
    }

    return Decision.ACCEPTED;
  }

  /**
   * True when the proposal turned "not enough evidence" into a verdict.
   *
   * <p>{@code INSUFFICIENT_EVIDENCE} means the platform has not measured enough to say anything. It
   * is not a low score and it is not a failure. An agent that reports it as either has converted an
   * absence of measurement into a claim about a learner, which is the specific harm this check
   * exists to prevent — and the one most likely to look reasonable in a rationale.
   */
  private boolean inferredAVerdictFromSparseEvidence(
      Proposal proposal, Map<String, MasteryStatus> masteryByskill) {

    if (proposal.inferredStatus() == null) {
      return false;
    }
    MasteryStatus recorded = masteryByskill.get(proposal.skillCode());
    boolean sparse = recorded == null || recorded == MasteryStatus.INSUFFICIENT_EVIDENCE;
    if (!sparse) {
      return false;
    }
    // Under sparse evidence the only honest inference is that more evidence is needed. Anything
    // else -- mastered, not mastered, failing -- is a verdict the platform has not reached.
    return !"INSUFFICIENT_EVIDENCE".equals(proposal.inferredStatus());
  }

  private static Optional<CurriculumGraph.SkillNode> findSkill(
      CurriculumGraph curriculum, String skillCode) {
    if (skillCode == null || curriculum == null) {
      return Optional.empty();
    }
    return curriculum.skills().stream()
        .filter(candidate -> skillCode.equals(candidate.stableCode()))
        .findFirst();
  }

  private static boolean objectiveBelongsToSkill(
      CurriculumGraph.SkillNode skill, String objectiveCode) {
    List<CurriculumGraph.Objective> objectives = skill.objectives();
    return objectives != null
        && objectives.stream().anyMatch(objective -> objectiveCode.equals(objective.code()));
  }

  private void record(Proposal proposal, Decision decision) {
    meterRegistry.counter(DECISION_METRIC, "decision", decision.tag()).increment();

    if (decision.accepted()) {
      return;
    }

    // A refusal is a disagreement between an agent and the deterministic policy, and it is worth
    // seeing. Logged at INFO rather than WARN because disagreement is expected behaviour for a
    // proposing agent -- what would be alarming is the rate, which the counter carries.
    LOGGER.atInfo()
        .addKeyValue("operation", "ai.diagnostic.gate")
        .addKeyValue("decision", decision.tag())
        .addKeyValue("skillCode", proposal.skillCode())
        .addKeyValue("objectiveCode", proposal.objectiveCode())
        .log("diagnostic proposal refused by deterministic policy");
  }
}
