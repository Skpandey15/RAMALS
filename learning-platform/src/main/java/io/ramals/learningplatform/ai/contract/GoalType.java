package io.ramals.learningplatform.ai.contract;

/**
 * Kind of goal a learner is pursuing.
 *
 * <p>Only {@link #LEARNING_DOMAIN} is produced today: the authoritative learner model carries a
 * single domain goal. The remaining values are declared so that adding one later is not a breaking
 * contract change, and so agents are written against a general goal rather than against "the
 * learner's one domain".
 *
 * <p>A value being nameable here is not evidence that it is supported.
 */
public enum GoalType {
  LEARNING_DOMAIN,
  ACADEMIC_MASTERY,
  DEGREE_COMPETENCY,
  CAREER_ROLE,
  CAREER_TRANSITION
}
