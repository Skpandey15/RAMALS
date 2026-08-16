package io.ramals.learningplatform.ai.contract;

/**
 * Deadline class governing an AI call (M1-ADR-001).
 *
 * <p>Where a route-specific ceiling in Doc 04 is tighter than the class ceiling, the route governs.
 * A route may never invoke the broader class limit to exceed its own budget.
 */
public enum InteractionClass {
  FAST,
  INTERACTIVE_AI,
  ASSESSMENT_PROPOSAL,
  LIMITED_DURABLE
}
