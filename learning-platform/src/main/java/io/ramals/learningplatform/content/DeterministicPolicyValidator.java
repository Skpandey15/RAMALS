package io.ramals.learningplatform.content;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Curriculum rules. The stage that asks whether this item is even about something the platform
 * teaches.
 *
 * <p>Runs against the curriculum graph rather than against the candidate's own claims, because the
 * failure being caught here is content that is internally consistent and externally wrong: a
 * well-formed item about a skill this curriculum does not contain, or filed under an objective
 * belonging to a different skill. Both look fine in isolation and both corrupt the evidence for
 * whichever skill they end up attributed to.
 */
@Component
public class DeterministicPolicyValidator implements ContentValidator {

  @Override
  public ValidationStage stage() {
    return ValidationStage.DETERMINISTIC_POLICY;
  }

  @Override
  public Optional<String> reject(CandidateContent candidate, ValidationContext context) {
    Optional<CurriculumGraph> graph = context.curriculum();
    if (graph.isEmpty()) {
      // No curriculum to check against is not a pass. Content admitted while the rules were
      // unavailable is content nobody ever checked.
      return Optional.of("no published curriculum available to validate against");
    }

    Optional<CurriculumGraph.SkillNode> skill = graph.get().skills().stream()
        .filter(node -> node.stableCode().equals(candidate.skillCode()))
        .findFirst();

    if (skill.isEmpty()) {
      return Optional.of("skill is not part of this curriculum version");
    }

    if (candidate.objectiveCode() != null) {
      List<CurriculumGraph.Objective> objectives = skill.get().objectives();
      boolean belongs = objectives != null
          && objectives.stream()
              .anyMatch(objective -> candidate.objectiveCode().equals(objective.code()));
      if (!belongs) {
        return Optional.of("objective does not belong to this skill");
      }
    }

    List<String> acceptedBands = skill.get().requiredDifficultyBands();
    if (acceptedBands != null
        && !acceptedBands.isEmpty()
        && !acceptedBands.contains(candidate.difficulty())) {
      // The skill declares which bands its evidence must come from. An item outside them would
      // produce evidence the mastery engine cannot use for this skill.
      return Optional.of("difficulty band is not accepted for this skill");
    }

    return Optional.empty();
  }
}
