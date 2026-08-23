package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.recommendation.RecommendationService;
import org.springframework.stereotype.Component;

/**
 * Hands the recomputed learner state to the existing durable adaptation path.
 *
 * <p>This step deliberately does not call the adaptation agent. It records the deterministic
 * recommendation decision, which enqueues adaptation work on the transactional outbox; the M2-T03
 * dispatcher then owns delivery, retry and terminal failure for the agent call itself. Duplicating
 * that machinery inside the workflow would give one agent call two independent retry budgets, and
 * the looser of the two would win.
 *
 * <p>The step therefore completes when the hand-off is durable, which is the only thing the
 * workflow can honestly claim to have achieved.
 */
@Component
public class AdaptationHandoffStep implements WorkflowAgentStep.Adaptation {

  private final MasteryService mastery;
  private final RecommendationService recommendations;

  public AdaptationHandoffStep(MasteryService mastery, RecommendationService recommendations) {
    this.mastery = mastery;
    this.recommendations = recommendations;
  }

  @Override
  public Result adapt(Run run) {
    MasterySnapshot snapshot =
        mastery
            .latestSnapshot(run.learnerId(), run.skillId(), run.curriculumVersionId())
            .orElse(null);
    if (snapshot == null) {
      // The preceding step wrote one, so its absence means the run is resuming against state that
      // moved underneath it. Reported as a failure rather than assumed away.
      return Result.failed("ADAPTATION_SNAPSHOT_ABSENT", null);
    }
    recommendations.recommend(snapshot, run.interactionId(), run.traceId());
    return Result.accepted("ADAPTATION_WORK_ENQUEUED", requestId(run));
  }

  /** Derived from the run so a retried hand-off correlates to the same step, not a new one. */
  static String requestId(Run run) {
    return "wf-adapt-" + run.id();
  }
}
