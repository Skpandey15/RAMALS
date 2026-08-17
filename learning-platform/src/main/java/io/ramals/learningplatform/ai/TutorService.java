package io.ramals.learningplatform.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles a tutor request and hands it to the AI plane.
 *
 * <p>Notice what this class does not have: {@code @Transactional}. Everything the request needs is
 * read first, in short transactions owned by the repositories; by the time the AI call happens, no
 * connection is held. The ordering is the design — read, close, then call — and
 * {@code RamalsAiTutorClient} asserts at runtime that it was respected, because a future
 * {@code @Transactional} added here would otherwise be invisible until production ran out of
 * connections.
 *
 * <p>Tutoring degrades independently: a learner who loses their tutor keeps their assessments,
 * mastery map and recommendations. But degradation is <em>named</em>. Returning an empty value would
 * make "tutoring is switched off here" and "the AI plane is broken right now" the same result, and
 * an outage that looks like a configuration choice is an outage nobody investigates.
 */
@Service
public class TutorService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TutorService.class);

  /** Doc 01 INTERACTIVE_AI hard deadline. The complete-response budget, since V1 does not stream. */
  static final long INTERACTIVE_AI_DEADLINE_MS = 12_000;

  private static final String OUTCOME_METRIC = "ramals.ai.tutor.outcome";

  private final TutorPort tutorPort;
  private final DomainContextAssembler domainContext;
  private final MeterRegistry meterRegistry;

  public TutorService(
      TutorPort tutorPort, DomainContextAssembler domainContext, MeterRegistry meterRegistry) {
    this.tutorPort = tutorPort;
    this.domainContext = domainContext;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Requests an explanation for a skill.
   *
   * @return a proposal, or an {@link TutorOutcome.Unavailable} naming why there is none. Never
   *     throws for an AI-side failure: a learner losing their tutor must not lose their session.
   */
  public TutorOutcome explain(
      String learnerRef, String skillCode, String masteryStatus, String locale) {

    // Read phase. Each repository call manages its own transaction and releases the connection
    // before returning, so nothing is held open across the network call below.
    Optional<DomainContext> domain = domainContext.forSkill(skillCode);
    if (domain.isEmpty()) {
      return unavailable(TutorUnavailableReason.UNKNOWN_SKILL, skillCode);
    }

    AiRequestEnvelope request = new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION,
        CorrelationContext.currentInteractionId(),
        UUID.randomUUID().toString(),
        // An opaque reference, not an identifier the AI plane could correlate across requests. The
        // AI plane is told which skill to explain, not who is asking.
        new LearnerRef(learnerRef, locale),
        new LearningContext(skillCode, null, null, masteryStatus, null),
        domain.get(),
        null,
        new Constraints(InteractionClass.INTERACTIVE_AI, (int) INTERACTIVE_AI_DEADLINE_MS,
            null, null, null),
        "EXPLAIN");

    try {
      TutorOutcome outcome =
          new TutorOutcome.Proposed(tutorPort.requestTutorResponse(request, INTERACTIVE_AI_DEADLINE_MS));
      meterRegistry.counter(OUTCOME_METRIC, "outcome", "proposed", "reason", "none").increment();
      return outcome;
    } catch (AiUnavailableException unavailable) {
      return unavailable(TutorUnavailableReason.fromCode(unavailable.code()), skillCode);
    }
  }

  /**
   * Records an unavailability and returns it as an outcome.
   *
   * <p>Every path through here increments a counter tagged with the reason, so an operator can see
   * the difference between a quiet tutor and a broken one on a dashboard rather than by reading
   * logs. The log level follows {@link TutorUnavailableReason#expected()}: a configuration state
   * logged at WARN would train someone to ignore the level that matters.
   */
  private TutorOutcome unavailable(TutorUnavailableReason reason, String skillCode) {
    meterRegistry
        .counter(OUTCOME_METRIC, "outcome", "unavailable", "reason", reason.code())
        .increment();

    String supportCode = CorrelationContext.currentInteractionId();
    var event = reason.expected() ? LOGGER.atInfo() : LOGGER.atWarn();
    event
        .addKeyValue("operation", "ai.tutor.request")
        .addKeyValue("errorCode", reason.code())
        .addKeyValue("expected", reason.expected())
        .addKeyValue("skillCode", skillCode)
        .log("tutoring unavailable; deterministic learning continues");

    return new TutorOutcome.Unavailable(reason, supportCode);
  }
}
