package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import io.ramals.learningplatform.recommendation.RecommendationDecidedEvent;
import java.nio.charset.StandardCharsets;
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
 *
 * <h2>Delivery limitation, accepted for MVP-1</h2>
 *
 * <p>{@code AFTER_COMMIT} isolates the AI call from the authoritative transaction, which is what it
 * is for. It is <strong>not durable delivery.</strong> The event lives in memory between commit and
 * listener completion, so a process failure in that window loses the comparison silently: the
 * learner's state is correct and complete, and no {@code ai_execution} row is ever written for that
 * decision.
 *
 * <p>That is acceptable here and only here, because the comparison is research and observability
 * input rather than learner-visible behaviour. Losing one is a gap in a metric series, not a
 * correctness fault. It would not be acceptable for anything the learner or an auditor depends on.
 *
 * <p>Durable delivery — a transactional outbox, or a broker — is recorded as technical debt rather
 * than built here: introducing one is an infrastructure decision with its own operational surface,
 * and doing it inside a release-blocking correction would be the wrong trade.
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
          .addKeyValue("interactionId", event.interactionId())
          .addKeyValue("traceId", event.traceId())
          .log("adaptation comparison not dispatched; already commissioned for this decision");
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
          .addKeyValue("interactionId", event.interactionId())
          .addKeyValue("traceId", event.traceId())
          .log("adaptation comparison failed; the deterministic recommendation is unaffected");
      executionRecorder.recordFailure(
          request, AGENT_TYPE, "ADAPTATION_COMPARISON_FAILED", startedAt, Instant.now());
    }
  }

  /**
   * A request id derived from what is being compared, not a fresh one per call.
   *
   * <p>{@code ai_execution} is unique on {@code (request_id, event_type)}, so this is what makes a
   * replay idempotent at the execution layer. A random id per invocation would commission a second
   * execution for the same decision and dispatch the agent twice.
   *
   * <p>The upstream flow already prevents most of that — a replayed submission finds the attempt
   * {@code COMPLETED} and returns without recomputing, so no second event is published at all. This
   * makes the guarantee hold by construction rather than by that upstream behaviour remaining true.
   */
  private static String requestIdFor(RecommendationDecidedEvent event) {
    String key = AGENT_TYPE + '|' + event.interactionId() + '|' + event.skillId();
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static AiRequestEnvelope envelopeFor(RecommendationDecidedEvent event) {
    return new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION,
        event.interactionId(),
        requestIdFor(event),
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
