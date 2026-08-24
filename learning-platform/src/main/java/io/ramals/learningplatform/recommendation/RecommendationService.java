package io.ramals.learningplatform.recommendation;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces a deterministic recommendation for a mastery snapshot and records its provenance. Both
 * the decision record and the current-surface recommendation are idempotent on the snapshot, so a
 * repeated recompute never duplicates a decision.
 */
@Service
public class RecommendationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationService.class);

  private final RecommendationPolicy policy;
  private final RecommendationRepository repository;
  private final LearnerService learnerService;

  public RecommendationService(
      RecommendationPolicy policy,
      RecommendationRepository repository,
      LearnerService learnerService) {
    this.policy = policy;
    this.repository = repository;
    this.learnerService = learnerService;
  }

  @Transactional
  public RecommendationResult recommend(
      MasterySnapshot snapshot, String interactionId, String traceId) {
    RecommendationDecision decision = policy.decide(snapshot);
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(interactionId, traceId)) {
      BusinessEventLogger.info(LOGGER, "recommendation.decided", "Learning recommendation decided",
          Map.ofEntries(
              Map.entry("entityType", "Recommendation"),
              Map.entry("entityId", snapshot.id()),
              Map.entry("snapshotId", snapshot.id()),
              Map.entry("learnerId", snapshot.learnerId()),
              Map.entry("skillId", snapshot.skillId()),
              Map.entry("policyVersion", RecommendationPolicy.POLICY_VERSION),
              Map.entry("outcome", "SUCCESS"),
              Map.entry("recommendedAction", decision.action()),
              Map.entry("reasonCode", decision.reasonCode())));
    }
    DecisionRecord decisionRecord = repository.appendDecisionRecord(
        snapshot, decision, RecommendationPolicy.POLICY_VERSION, interactionId, traceId);
    LearningRecommendation recommendation = repository.appendRecommendation(
        snapshot, decision, decisionRecord.id());
    RecommendationRepository.AdaptationWork adaptationWork =
        repository.appendAdaptationWork(decisionRecord);
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(interactionId, traceId)) {
      BusinessEventLogger.info(LOGGER, "recommendation.persisted",
          "Learning recommendation persisted",
          Map.of(
              "entityType", "Recommendation",
              "entityId", recommendation.id(),
              "decisionRecordId", decisionRecord.id(),
              "snapshotId", snapshot.id(),
              "outcome", "SUCCESS"));
    }

    // The durable dispatcher owns delivery. No remote call or volatile event is part of this
    // transaction; commit makes the work recoverable and a worker claims it afterwards.

    return new RecommendationResult(
        recommendation, decisionRecord.id(), adaptationWork.workId(), adaptationWork.requestId());
  }

  /**
   * One recommendation and the durable adaptation work it enqueued, in the same transaction.
   *
   * <p>The adaptation request id is surfaced so a caller can record what it actually handed to the
   * outbox. Correlation that is reconstructed rather than reported is correlation that silently
   * stops joining.
   */
  public record RecommendationResult(
      LearningRecommendation recommendation,
      UUID decisionRecordId,
      UUID adaptationWorkId,
      String adaptationRequestId) {}

  @Transactional(readOnly = true)
  public List<LearningRecommendation> currentRecommendations(String subject) {
    return learnerService.findLearner(subject)
        .map(Learner::id)
        .map(repository::findCurrentByLearner)
        .orElseGet(List::of);
  }

}
