package io.ramals.learningplatform.recommendation;

import java.util.UUID;

/**
 * Published when a deterministic recommendation has been decided and persisted.
 *
 * <p>Exists so the AI adaptation comparison can run <em>after</em> the authoritative transaction
 * commits. {@code RecommendationService.recommend()} is transactional and its only caller,
 * {@code DiagnosticSubmissionService.submit()}, is transactional too, so the whole submission is one
 * transaction. Calling the AI plane from inside it would hold a database connection open across a
 * network call with a twelve-second deadline — the precise failure {@code TutorService} was written
 * to avoid.
 *
 * <p>Carrying the decision on the event rather than re-reading it also keeps the {@code ai} package
 * off the authoritative repositories, which ArchUnit enforces.
 *
 * <p>There is deliberately no learner identifier here. The adaptation listener does not need one --
 * the AI plane is told which skill was decided, not whose it is -- and an identifier present on the
 * event is an identifier available to every future consumer of it. Nothing that does not need
 * learner identity should be handed it.
 */
public record RecommendationDecidedEvent(
    UUID skillId,
    RecommendationDecision decision,
    String interactionId,
    String traceId) {}
