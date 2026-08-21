package io.ramals.learningplatform.recommendation;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
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
  private final ApplicationEventPublisher events;

  public RecommendationService(
      RecommendationPolicy policy,
      RecommendationRepository repository,
      LearnerService learnerService,
      ApplicationEventPublisher events) {
    this.policy = policy;
    this.repository = repository;
    this.learnerService = learnerService;
    this.events = events;
  }

  @Transactional
  public LearningRecommendation recommend(
      MasterySnapshot snapshot, String interactionId, String traceId) {
    RecommendationDecision decision = policy.decide(snapshot);
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
            Map.entry("reasonCode", decision.reasonCode()),
            Map.entry("interactionId", correlationValue(interactionId, "interactionId")),
            Map.entry("traceId", correlationValue(traceId, "traceId"))));
    DecisionRecord decisionRecord = repository.appendDecisionRecord(
        snapshot, decision, RecommendationPolicy.POLICY_VERSION, interactionId, traceId);
    LearningRecommendation recommendation = repository.appendRecommendation(
        snapshot, decision, decisionRecord.id());
    BusinessEventLogger.info(LOGGER, "recommendation.persisted",
        "Learning recommendation persisted",
        Map.of(
            "entityType", "Recommendation",
            "entityId", recommendation.id(),
            "decisionRecordId", decisionRecord.id(),
            "snapshotId", snapshot.id(),
            "outcome", "SUCCESS",
            "interactionId", correlationValue(interactionId, "interactionId"),
            "traceId", correlationValue(traceId, "traceId")));

    // The AI adaptation comparison listens for this and runs after this transaction commits. It is
    // deliberately not called inline: M1-T11 makes the comparison research input, and the
    // deterministic recommendation above is already durable and authoritative whether or not the
    // agent ever answers. Calling the plane from inside this transaction would also hold a database
    // connection across a network call with a twelve-second deadline.
    events.publishEvent(new RecommendationDecidedEvent(
        snapshot.skillId(), decision, interactionId, traceId));

    return recommendation;
  }

  @Transactional(readOnly = true)
  public List<LearningRecommendation> currentRecommendations(String subject) {
    return learnerService.findLearner(subject)
        .map(Learner::id)
        .map(repository::findCurrentByLearner)
        .orElseGet(List::of);
  }

  private static String correlationValue(String supplied, String mdcKey) {
    if (supplied != null && !supplied.isBlank()) {
      return supplied;
    }
    String current = MDC.get(mdcKey);
    return current == null ? "" : current;
  }
}
