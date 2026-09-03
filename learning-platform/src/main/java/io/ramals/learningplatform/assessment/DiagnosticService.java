package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Diagnostic attempt lifecycle. Attempt creation is retry-safe: a repeated Idempotency-Key returns
 * the same logical attempt, and the one-active-attempt invariant is enforced even when two requests
 * with different keys race. Learner identity is always taken from the authenticated subject.
 */
@Service
public class DiagnosticService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticService.class);

  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

  private final AssessmentRepository repository;
  private final LearnerService learnerService;
  private final DiagnosticFormSelector selector;

  public DiagnosticService(
      AssessmentRepository repository,
      LearnerService learnerService,
      DiagnosticFormSelector selector) {
    this.repository = repository;
    this.learnerService = learnerService;
    this.selector = selector;
  }

  @Transactional
  public AttemptCreation createAttempt(String subject, String domainCode, String idempotencyKey) {
    String key = normalizeIdempotencyKey(idempotencyKey);
    String normalizedDomain = domainCode.toUpperCase(Locale.ROOT);
    Learner learner = learnerService.currentLearner(subject);
    ResolvedDiagnostic diagnostic = repository.findPublishedDiagnostic(normalizedDomain)
        .orElseThrow(() -> new DiagnosticNotFoundException(normalizedDomain));
    UUID versionId = diagnostic.assessmentVersionId();

    Optional<AssessmentAttempt> byKey = repository.findByIdempotency(learner.id(), versionId, key);
    if (byKey.isPresent()) {
      return new AttemptCreation(byKey.get(), diagnostic, false);
    }
    Optional<AssessmentAttempt> active = repository.findActiveAttempt(learner.id(), versionId);
    if (active.isPresent()) {
      return new AttemptCreation(active.get(), diagnostic, false);
    }
    try {
      AssessmentAttempt created = repository.insertAttempt(
          learner.id(), versionId, key, DiagnosticFormSelector.SELECTION_POLICY_VERSION);
      selectForm(created, learner.id(), versionId);
      BusinessEventLogger.info(LOGGER, "assessment.started", "Diagnostic assessment started",
          Map.of("entityType", "ASSESSMENT_ATTEMPT", "entityId", created.id(),
              "learnerId", learner.id(), "outcome", "SUCCESS"));
      return new AttemptCreation(created, diagnostic, true);
    } catch (DuplicateKeyException concurrentCreate) {
      AssessmentAttempt resolved = repository.findByIdempotency(learner.id(), versionId, key)
          .or(() -> repository.findActiveAttempt(learner.id(), versionId))
          .orElseThrow(() -> concurrentCreate);
      return new AttemptCreation(resolved, diagnostic, false);
    }
  }

  @Transactional(readOnly = true)
  public AttemptDetail getAttempt(String subject, String domainCode, String rawAttemptId) {
    UUID attemptId = parseAttemptId(rawAttemptId);
    Learner learner = learnerService.findLearner(subject)
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    AssessmentAttempt attempt = repository.findAttempt(attemptId)
        .filter(candidate -> candidate.learnerId().equals(learner.id()))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    ResolvedDiagnostic diagnostic = repository.findDiagnosticByVersionId(attempt.assessmentVersionId())
        .filter(resolved -> resolved.domainCode().equalsIgnoreCase(domainCode))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    return new AttemptDetail(attempt, diagnostic,
        repository.findPresentedItems(attempt.id(), attempt.assessmentVersionId()));
  }

  /**
   * Assembles this attempt's form and records it.
   *
   * <p>Runs inside the creating transaction, so an attempt never becomes visible without the
   * questions it is an attempt at. It runs only on the branch that actually inserted an attempt:
   * every other branch of {@link #createAttempt} returns an attempt that already exists, and
   * re-selecting for it would either duplicate the form or -- worse, if the second draw differed --
   * change the questions under a learner who is midway through answering them.
   */
  private void selectForm(AssessmentAttempt attempt, UUID learnerId, UUID assessmentVersionId) {
    Instant recencySince = selector.recencyHorizon(Instant.now());
    List<EligibleItem> pool =
        repository.findEligibleItems(assessmentVersionId, learnerId, recencySince);
    DiagnosticForm form = selector.select(pool, ThreadLocalRandom.current());
    repository.insertSelectedItems(attempt.id(), form.items());
    BusinessEventLogger.info(LOGGER, "assessment.form.selected", "Diagnostic form selected",
        Map.of("entityType", "ASSESSMENT_ATTEMPT", "entityId", attempt.id(),
            "learnerId", learnerId,
            "selectionPolicy", DiagnosticFormSelector.SELECTION_POLICY_VERSION,
            "poolSize", form.poolSize(), "itemCount", form.items().size(),
            "skillsCovered", form.skillsCovered(),
            "difficultiesCovered", form.difficultiesCovered(),
            "recentlyPresentedReused", form.recentlyPresentedReused(),
            "outcome", "SUCCESS"));
  }

  private UUID parseAttemptId(String rawAttemptId) {
    try {
      return UUID.fromString(rawAttemptId);
    } catch (IllegalArgumentException notAUuid) {
      throw new AttemptNotFoundException(rawAttemptId);
    }
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidIdempotencyKeyException("An Idempotency-Key header is required.");
    }
    String trimmed = idempotencyKey.trim();
    if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new InvalidIdempotencyKeyException(
          "The Idempotency-Key header exceeds " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
    }
    return trimmed;
  }
}
