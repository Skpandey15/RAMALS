package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.LearnerRef;
import io.ramals.learningplatform.ai.contract.LearningContext;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles a tutor request and hands it to the AI plane.
 *
 * <p>Notice what this class does not have: {@code @Transactional}. Everything the request needs is
 * read first, in its own short transaction owned by the repositories; by the time the AI call
 * happens, no connection is held. The ordering is the design — read, close, then call — and
 * {@code RamalsAiTutorClient} asserts at runtime that it was respected, because a future
 * {@code @Transactional} added here would otherwise be invisible until production ran out of
 * connections.
 *
 * <p>Tutoring degrades independently. Every failure path returns empty rather than throwing, so a
 * caller that cannot get a tutor response still serves the learner their assessments, mastery map
 * and recommendations. That is the M1-T08 acceptance criterion, and expressing it as an
 * {@link Optional} rather than an exception is what makes ignoring it the natural thing to do.
 */
@Service
public class TutorService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TutorService.class);

  /** Doc 01 INTERACTIVE_AI hard deadline. The complete-response budget, since V1 does not stream. */
  static final long INTERACTIVE_AI_DEADLINE_MS = 12_000;

  private final TutorPort tutorPort;
  private final DomainContextAssembler domainContext;

  public TutorService(TutorPort tutorPort, DomainContextAssembler domainContext) {
    this.tutorPort = tutorPort;
    this.domainContext = domainContext;
  }

  /**
   * Requests an explanation for a skill.
   *
   * @return the proposal, or empty when the AI plane is unavailable. Never throws for an AI-side
   *     failure: a learner losing their tutor must not lose their session.
   */
  public Optional<AiProposalEnvelope> explain(
      String learnerRef, String skillCode, String masteryStatus, String locale) {

    // Read phase. Each repository call manages its own transaction and releases the connection
    // before returning, so nothing is held open across the network call below.
    Optional<DomainContext> domain = domainContext.forSkill(skillCode);
    if (domain.isEmpty()) {
      LOGGER.atWarn()
          .addKeyValue("operation", "ai.tutor.request")
          .addKeyValue("errorCode", "UNKNOWN_SKILL")
          .addKeyValue("skillCode", skillCode)
          .log("refused a tutor request for a skill the platform does not define");
      return Optional.empty();
    }

    AiRequestEnvelope request = new AiRequestEnvelope(
        AiRequestEnvelope.CONTRACT_VERSION,
        CorrelationContext.currentInteractionId(),
        java.util.UUID.randomUUID().toString(),
        // An opaque reference, not a learner identifier the AI plane could correlate across
        // requests. The AI plane is told which skill to explain, not who is asking.
        new LearnerRef(learnerRef, locale),
        new LearningContext(skillCode, null, null, masteryStatus, null),
        domain.get(),
        null,
        new Constraints(InteractionClass.INTERACTIVE_AI, (int) INTERACTIVE_AI_DEADLINE_MS,
            null, null, null),
        "EXPLAIN");

    try {
      return Optional.of(tutorPort.requestTutorResponse(request, INTERACTIVE_AI_DEADLINE_MS));
    } catch (AiUnavailableException unavailable) {
      // Logged at INFO, not ERROR. An unavailable tutor is a designed-for state, and paging someone
      // every time a circuit opens would train them to ignore the alert that matters.
      LOGGER.atInfo()
          .addKeyValue("operation", "ai.tutor.request")
          .addKeyValue("errorCode", unavailable.code())
          .log("tutoring unavailable; deterministic learning continues");
      return Optional.empty();
    }
  }
}
