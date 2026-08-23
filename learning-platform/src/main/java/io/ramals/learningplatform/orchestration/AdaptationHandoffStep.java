package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.recommendation.RecommendationService;
import java.util.Objects;
import java.util.UUID;
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
  public Result adapt(Run run, UUID masterySnapshotId) {
    MasterySnapshot snapshot = mastery.snapshotById(masterySnapshotId).orElse(null);
    if (snapshot == null) {
      // The recompute step recorded this id, so its absence means the lineage is broken. Failing
      // is the safe answer: substituting whatever snapshot is current would compute the learner's
      // next step from state this workflow never evaluated.
      return Result.failed("ADAPTATION_SNAPSHOT_ABSENT", null);
    }
    if (!ownedByRun(snapshot, run)) {
      return Result.failed("ADAPTATION_SNAPSHOT_FOREIGN", null);
    }
    // The real, durable identity of the enqueued work -- not a workflow-local string. This is what
    // makes learning_workflow_step.request_id join to core.agent_work_outbox.request_id and, later,
    // to the ai_execution the dispatcher records for it.
    return Result.accepted(
        "ADAPTATION_WORK_ENQUEUED",
        recommendations
            .recommend(snapshot, run.interactionId(), run.traceId())
            .adaptationRequestId());
  }

  /**
   * Whether the snapshot really belongs to this run.
   *
   * <p>Cheap, and it turns a lineage bug into a refusal instead of an adaptation computed for the
   * wrong learner or skill.
   */
  private static boolean ownedByRun(MasterySnapshot snapshot, Run run) {
    return Objects.equals(snapshot.learnerId(), run.learnerId())
        && Objects.equals(snapshot.skillId(), run.skillId())
        && Objects.equals(snapshot.curriculumVersionId(), run.curriculumVersionId());
  }
}
