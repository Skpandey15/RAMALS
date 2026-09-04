package io.ramals.learningplatform.assessment;

import java.util.Set;

/**
 * Raised when an adaptive attempt cannot be assembled because this learner has already been shown
 * every unseen item the version's pool has to offer, for every skill in scope.
 *
 * <p>Deliberately distinct from {@link EmptyItemPoolException}: that one signals a broken
 * publication invariant (V017 makes a genuinely empty verified pool unreachable). This one signals
 * an expected, reachable outcome of the no-repeat guarantee working correctly -- a learner who has
 * exhausted the bank -- and must never be papered over by silently recycling a question or padding
 * the packet, so it is reported rather than swallowed. A partial packet (some but not all skills
 * exhausted) is not this exception: see {@link AdaptivePacket#skillsWithNoUnseenStock()}, which
 * {@link DiagnosticService} reports without refusing the attempt, the same "shorter form" tolerance
 * {@link DiagnosticFormProperties} already documents for V1.
 */
public class AssessmentBankExhaustedException extends RuntimeException {

  private final Set<String> exhaustedSkillCodes;

  public AssessmentBankExhaustedException(Set<String> exhaustedSkillCodes) {
    super("No unseen scoreable items remain for this learner across skills: " + exhaustedSkillCodes);
    this.exhaustedSkillCodes = Set.copyOf(exhaustedSkillCodes);
  }

  public Set<String> exhaustedSkillCodes() {
    return exhaustedSkillCodes;
  }
}
