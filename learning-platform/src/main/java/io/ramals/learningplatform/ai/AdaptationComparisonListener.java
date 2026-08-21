package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import io.ramals.learningplatform.recommendation.RecommendationDecidedEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Makes the adaptation agent reachable from the learner journey.
 *
 * <p>M1-T11 built the port, the client, {@link AdaptationService} and
 * {@link AdaptationProposalGate} with their comparison and fallback semantics — and nothing consumed
 * the service. M1-T18 found it: every piece of adaptation existed and no learner action could ever
 * reach any of it, so no learner journey wrote an {@code ai_execution} row either.
 *
 * <p>Runs on {@link TransactionPhase#AFTER_COMMIT}, which is the whole point of routing this through
 * an event. {@code RecommendationService.recommend()} is transactional and is called from inside
 * {@code DiagnosticSubmissionService.submit()}'s transaction; an inline call would hold a database
 * connection open across a network call with a twelve-second deadline, which is the failure
 * {@code TutorService} was written to avoid.
 *
 * <p>The consequence is deliberate and is the correct behaviour: <strong>a failure here cannot roll
 * back the submission, and must not.</strong> By the time this runs the learner's evidence, mastery
 * and recommendation are durable and authoritative. The agent may agree, disagree, or never answer;
 * none of those change what the learner is told. Disagreement is a metric, not a branch.
 */
@Component
public class AdaptationComparisonListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(AdaptationComparisonListener.class);

  /** Doc 01 INTERACTIVE_AI hard deadline, as used by the tutor path. */
  static final long ADAPTATION_DEADLINE_MS = 12_000;

  private static final String AGENT_TYPE = "ADAPTATION";

  private final AdaptationService adaptationService;
  private final AiExecutionRecorder executionRecorder;

  public AdaptationComparisonListener(
      AdaptationService adaptationService, AiExecutionRecorder executionRecorder) {
    this.adaptationService = adaptationService;
    this.executionRecorder = executionRecorder;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRecommendationDecided(RecommendationDecidedEvent event) {
    AiRequestEnvelope request = envelopeFor(event);
    Instant startedAt = Instant.now();

    // Commissioning first, so a duplicate or disallowed dispatch is recorded rather than silently
    // skipped -- the same discipline the assessment intake path follows.
    AiExecutionCommission commission = executionRecorder.commission(request, AGENT_TYPE);
    if (!commission.dispatchAllowed()) {
      LOGGER.atInfo()
          .addKeyValue("operation", "ai.adaptation.compare")
          .addKeyValue("outcome", "NOT_DISPATCHED")
          .log("adaptation comparison not dispatched");
      return;
    }

    try {
      AdaptationService.Outcome outcome =
          adaptationService.compare(request, event.decision(), ADAPTATION_DEADLINE_MS);

      if (outcome.proposal() == null) {
        // AdaptationService swallows AiUnavailableException and returns the deterministic decision
        // with no proposal. That is the fallback M1-T11 specifies; record it as a failed execution
        // so the absence is accounted for rather than invisible.
        executionRecorder.recordFailure(
            request, AGENT_TYPE, "AI_UNAVAILABLE", startedAt, Instant.now());
        return;
      }

      executionRecorder.recordSuccess(request, outcome.proposal(), startedAt, Instant.now());
    } catch (RuntimeException unexpected) {
      // The learner is already served and their state is already committed. An adaptation failure
      // must not surface to them and must not escape into the caller's thread, so it is recorded and
      // contained here.
      LOGGER.atWarn()
          .setCause(unexpected)
          .addKeyValue("operation", "ai.adaptation.compare")
          .addKeyValue("outcome", "FAILED")
          .log("adaptation comparison failed; the deterministic recommendation is unaffected");
      executionRecorder.recordFailure(
          request, AGENT_TYPE, "ADAPTATION_COMPARISON_FAILED", startedAt, Instant.now());
    }
  }

  private static AiRequestEnvelope envelopeFor(RecommendationDecidedEvent event) {
    return new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION,
        event.interactionId(),
        UUID.randomUUID().toString(),
        // Opaque and single-use, as on the tutor path: the plane is told which skill was decided,
        // not which learner it belongs to, and cannot link this call to any other.
        new LearnerRef(UUID.randomUUID().toString(), Locale.ENGLISH.toLanguageTag()),
        new LearningContext(
            event.skillId() == null ? null : event.skillId().toString(),
            null, null, null, null),
        null,
        null,
        new Constraints(
            InteractionClass.INTERACTIVE_AI, (int) ADAPTATION_DEADLINE_MS, null, null, null),
        "ADAPT");
  }
}
