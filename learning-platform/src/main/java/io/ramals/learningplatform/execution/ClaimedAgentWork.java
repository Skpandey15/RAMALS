package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.util.UUID;

/** One leased work item plus the immutable decision facts needed by the adaptation handler. */
public record ClaimedAgentWork(
    UUID id,
    String requestId,
    String interactionId,
    String traceId,
    String agentType,
    String capability,
    UUID sourceDecisionId,
    UUID learnerId,
    UUID skillId,
    RecommendedAction recommendedAction,
    String reasonCode,
    int attemptCount,
    String leaseOwner) {

  /**
   * Compatibility constructor for unit fixtures written before the learner was carried on the
   * claim. Production claims always use the learner id from the authoritative decision row; the
   * fallback exists only so older isolated dispatcher tests do not become a second production
   * construction path.
   */
  public ClaimedAgentWork(
      UUID id,
      String requestId,
      String interactionId,
      String traceId,
      String agentType,
      String capability,
      UUID sourceDecisionId,
      UUID skillId,
      RecommendedAction recommendedAction,
      String reasonCode,
      int attemptCount,
      String leaseOwner) {
    this(
        id,
        requestId,
        interactionId,
        traceId,
        agentType,
        capability,
        sourceDecisionId,
        UUID.randomUUID(),
        skillId,
        recommendedAction,
        reasonCode,
        attemptCount,
        leaseOwner);
  }
}
