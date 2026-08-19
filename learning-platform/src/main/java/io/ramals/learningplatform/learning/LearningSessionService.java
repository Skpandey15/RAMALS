package io.ramals.learningplatform.learning;

import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Owns the learning-session lifecycle. Sessions are durable state, so a restart resumes the open
 * session rather than forking a new one. Transitions are short optimistic writes guarded by the
 * session version, so concurrent commands cannot both win and no transaction is held across a
 * learner's think time. Every command is correlated by interactionId.
 */
@Service
public class LearningSessionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(LearningSessionService.class);

  private final LearningSessionRepository repository;
  private final LearningSessionPolicy policy;
  private final LearnerService learnerService;
  private final CurriculumService curriculumService;

  public LearningSessionService(
      LearningSessionRepository repository,
      LearningSessionPolicy policy,
      LearnerService learnerService,
      CurriculumService curriculumService) {
    this.repository = repository;
    this.policy = policy;
    this.learnerService = learnerService;
    this.curriculumService = curriculumService;
  }

  @Transactional
  public SessionStartResult start(String subject, String domainCode, String versionCode) {
    Learner learner = learnerService.currentLearner(subject);
    UUID curriculumVersionId = curriculumService.graph(domainCode, versionCode).curriculumVersionId();
    String interactionId = CorrelationContext.currentInteractionId();

    return repository.findOpenSession(learner.id(), curriculumVersionId)
        .map(session -> new SessionStartResult(session, false))
        .orElseGet(() -> createSession(learner.id(), curriculumVersionId, interactionId));
  }

  private SessionStartResult createSession(
      UUID learnerId, UUID curriculumVersionId, String interactionId) {
    try {
      LearningSession session = repository.insertSession(learnerId, curriculumVersionId, interactionId);
      repository.insertTransition(session.id(), null, LearningSessionStatus.ACTIVE, "START",
          session.version(), interactionId, CorrelationContext.currentTraceId());
      BusinessEventLogger.info(LOGGER, "learning.session.started", "Learning session started",
          Map.of("entityType", "LEARNING_SESSION", "entityId", session.id(),
              "stateTo", LearningSessionStatus.ACTIVE, "outcome", "SUCCESS"));
      return new SessionStartResult(session, true);
    } catch (DuplicateKeyException concurrentStart) {
      LearningSession session = repository.findOpenSession(learnerId, curriculumVersionId)
          .orElseThrow(() -> concurrentStart);
      return new SessionStartResult(session, false);
    }
  }

  @Transactional
  public LearningSession transition(
      String subject, String rawSessionId, SessionTransitionRequest request) {
    UUID sessionId = parseSessionId(rawSessionId);
    Learner learner = learnerService.findLearner(subject)
        .orElseThrow(() -> new LearningSessionNotFoundException(rawSessionId));
    LearningSession session = repository.findByIdAndLearner(sessionId, learner.id())
        .orElseThrow(() -> new LearningSessionNotFoundException(rawSessionId));

    if (session.version() != request.expectedVersion()) {
      throw new SessionConflictException(rawSessionId);
    }
    LearningSessionStatus target = policy.target(session.status(), request.command())
        .orElseThrow(() -> new InvalidSessionTransitionException(session.status(), request.command()));

    String checkpointJson = checkpointJson(request.checkpoint());
    int newVersion = session.version() + 1;
    String interactionId = CorrelationContext.currentInteractionId();
    boolean applied = repository.applyTransition(
        sessionId, request.expectedVersion(), target, newVersion, request.command(),
        interactionId, checkpointJson);
    if (!applied) {
      throw new SessionConflictException(rawSessionId);
    }
    repository.insertTransition(sessionId, session.status(), target, request.command().name(),
        newVersion, interactionId, CorrelationContext.currentTraceId());
    LearningSession transitioned = repository.findByIdAndLearner(sessionId, learner.id())
        .orElseThrow(() -> new LearningSessionNotFoundException(rawSessionId));
    BusinessEventLogger.info(LOGGER, "learning.session.completed", "Learning session transition completed",
        Map.of("entityType", "LEARNING_SESSION", "entityId", sessionId,
            "stateFrom", session.status(), "stateTo", target, "outcome", "SUCCESS"));
    return transitioned;
  }

  @Transactional(readOnly = true)
  public LearningSession get(String subject, String rawSessionId) {
    UUID sessionId = parseSessionId(rawSessionId);
    return learnerService.findLearner(subject)
        .flatMap(learner -> repository.findByIdAndLearner(sessionId, learner.id()))
        .orElseThrow(() -> new LearningSessionNotFoundException(rawSessionId));
  }

  @Transactional(readOnly = true)
  public List<LearningSession> list(String subject) {
    return learnerService.findLearner(subject)
        .map(learner -> repository.findByLearner(learner.id()))
        .orElseGet(List::of);
  }

  private String checkpointJson(JsonNode checkpoint) {
    if (checkpoint == null || checkpoint.isNull()) {
      return null;
    }
    if (!checkpoint.isObject()) {
      throw new IllegalArgumentException("checkpoint must be a JSON object.");
    }
    return checkpoint.toString();
  }

  private UUID parseSessionId(String rawSessionId) {
    try {
      return UUID.fromString(rawSessionId);
    } catch (IllegalArgumentException notAUuid) {
      throw new LearningSessionNotFoundException(rawSessionId);
    }
  }
}
