package io.ramals.learningplatform.recommendation;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces a deterministic recommendation for a mastery snapshot and records its provenance. Both
 * the decision record and the current-surface recommendation are idempotent on the snapshot, so a
 * repeated recompute never duplicates a decision.
 */
@Service
public class RecommendationService {

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
  public LearningRecommendation recommend(
      MasterySnapshot snapshot, String interactionId, String traceId) {
    RecommendationDecision decision = policy.decide(snapshot);
    DecisionRecord decisionRecord = repository.appendDecisionRecord(
        snapshot, decision, RecommendationPolicy.POLICY_VERSION, interactionId, traceId);
    return repository.appendRecommendation(snapshot, decision, decisionRecord.id());
  }

  @Transactional(readOnly = true)
  public List<LearningRecommendation> currentRecommendations(String subject) {
    return learnerService.findLearner(subject)
        .map(Learner::id)
        .map(repository::findCurrentByLearner)
        .orElseGet(List::of);
  }
}
