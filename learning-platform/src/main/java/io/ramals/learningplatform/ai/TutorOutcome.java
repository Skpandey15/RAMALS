package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;

/**
 * The result of asking for a tutor response: either a proposal, or an explicit reason there is none.
 *
 * <p>A sealed type rather than an {@code Optional}. An empty optional says "nothing here" and
 * nothing else, which silently merges a deliberate configuration with a live outage — and a caller
 * handling the empty case has no way to tell a learner whether tutoring is switched off or
 * temporarily broken, nor to decide whether the fact is worth a metric.
 *
 * <p>Being sealed also means a caller that pattern-matches is told by the compiler when a new
 * outcome appears, instead of falling through a default branch written before it existed.
 */
public sealed interface TutorOutcome {

  /** A tutor proposal was produced. Non-authoritative, like every agent proposal. */
  record Proposed(AiProposalEnvelope proposal) implements TutorOutcome {
  }

  /**
   * No proposal, and why.
   *
   * @param reason the operational fact, distinguishing a configuration state from a failure
   * @param supportCode the interactionId a learner can quote; safe to display and carries no secret
   */
  record Unavailable(TutorUnavailableReason reason, String supportCode) implements TutorOutcome {
  }

  /** Convenience for callers that only care whether there is something to render. */
  default boolean hasProposal() {
    return this instanceof Proposed;
  }
}
