package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.assessment.DiagnosticSubmissionRequest.ItemResponse;
import io.ramals.learningplatform.curriculum.AssessmentItemType;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.evidence.SkillEvidenceInput;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.recommendation.RecommendationService;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Finalizes a diagnostic attempt exactly once. The attempt row is locked for the duration of the
 * transaction; validation, response persistence, and finalization all commit together or not at
 * all. A submission to an already-completed attempt deterministically returns the original result
 * without writing anything.
 */
@Service
public class DiagnosticSubmissionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticSubmissionService.class);

  private final AssessmentRepository repository;
  private final LearnerService learnerService;
  private final DiagnosticScorerV2 scorer;
  private final EvidenceService evidenceService;
  private final MasteryService masteryService;
  private final RecommendationService recommendationService;
  private final DiagnosticConfidenceService diagnosticConfidenceService;
  private final ObjectMapper objectMapper;

  public DiagnosticSubmissionService(
      AssessmentRepository repository,
      LearnerService learnerService,
      DiagnosticScorerV2 scorer,
      EvidenceService evidenceService,
      MasteryService masteryService,
      RecommendationService recommendationService,
      DiagnosticConfidenceService diagnosticConfidenceService,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.learnerService = learnerService;
    this.scorer = scorer;
    this.evidenceService = evidenceService;
    this.masteryService = masteryService;
    this.recommendationService = recommendationService;
    this.diagnosticConfidenceService = diagnosticConfidenceService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public SubmissionResult submit(
      String subject, String domainCode, String rawAttemptId, DiagnosticSubmissionRequest request) {
    UUID attemptId = parseAttemptId(rawAttemptId);
    Learner learner = learnerService.findLearner(subject)
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    AssessmentAttempt attempt = repository.findAttemptForUpdate(attemptId)
        .filter(candidate -> candidate.learnerId().equals(learner.id()))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    repository.findDiagnosticByVersionId(attempt.assessmentVersionId())
        .filter(resolved -> resolved.domainCode().equalsIgnoreCase(domainCode))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));

    switch (attempt.status()) {
      case "COMPLETED" -> {
        return buildResult(attempt);
      }
      case "IN_PROGRESS" -> {
        return score(attempt, request);
      }
      default -> throw new InvalidAttemptStateException(attempt.status());
    }
  }

  private SubmissionResult score(AssessmentAttempt attempt, DiagnosticSubmissionRequest request) {
    // Scoped to the attempt's selected form, not to the assessment version. With per-attempt
    // selection the pool is wider than what this learner was asked, so a response naming an
    // unselected item must be rejected rather than scored into evidence for a question that was
    // never on their paper.
    Map<UUID, AssessmentItemScoringView> views =
        repository.findPresentedItemScoringViews(attempt.id(), attempt.assessmentVersionId())
            .stream()
            .collect(Collectors.toMap(AssessmentItemScoringView::itemVersionId, Function.identity()));

    Set<UUID> answered = new HashSet<>();
    for (ItemResponse response : request.responses()) {
      UUID itemId = parseItemId(response.itemId());
      if (!answered.add(itemId)) {
        throw new InvalidSubmissionException("Duplicate response for item " + response.itemId());
      }
      AssessmentItemScoringView view = views.get(itemId);
      if (view == null) {
        throw new UnknownAssessmentItemException(response.itemId());
      }
      validateSelection(view, response.selectedOptions());
      boolean correct = scorer.isCorrect(view, response.selectedOptions());
      repository.insertResponse(attempt.id(), itemId, writeResponse(response.selectedOptions()), correct);
      // DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-023 §2): a no-op for the ordinary case where this item is not
      // named by any core.diagnostic_probe_provenance row. Runs inside this same transaction, so a
      // failure here rolls this response back too -- the same atomicity recordEvidence/recomputeMastery
      // below already rely on.
      diagnosticConfidenceService.recordObservationIfProbeResponse(
          attempt.id(), itemId, attempt.learnerId(), attempt.assessmentVersionId());
    }

    repository.completeAttempt(attempt.id());
    AssessmentAttempt completed = withStatus(attempt, "COMPLETED");
    SubmissionResult result = buildResult(completed);
    String interactionId = CorrelationContext.currentInteractionId();
    List<Evidence> evidence = recordEvidence(completed, result, interactionId);
    recomputeMastery(completed, evidence, interactionId);
    BusinessEventLogger.info(LOGGER, "assessment.submitted", "Diagnostic assessment submitted",
        Map.of("entityType", "ASSESSMENT_ATTEMPT", "entityId", attempt.id(),
            "stateFrom", "IN_PROGRESS", "stateTo", "COMPLETED",
            "evidenceCount", evidence.size(), "outcome", "SUCCESS"));
    BusinessEventLogger.info(LOGGER, "assessment.scored", "Diagnostic assessment scored",
        Map.of("entityType", "ASSESSMENT_ATTEMPT", "entityId", attempt.id(),
            "skillCount", result.skillScores().size(), "outcome", "SUCCESS"));
    return result;
  }

  private List<Evidence> recordEvidence(
      AssessmentAttempt attempt, SubmissionResult result, String interactionId) {
    // What each skill was measured on, read back from the responses this transaction just wrote.
    // Coverage travels with the evidence rather than being re-derived at mastery time, so a later
    // change to content cannot retroactively alter what an observation is deemed to have covered.
    Map<String, EvidenceCoverage> coverage = repository.findAttemptCoverage(attempt.id());
    List<SkillEvidenceInput> observations = result.skillScores().stream()
        .map(score -> new SkillEvidenceInput(
            score.skillCode(), score.observedScore(), score.normalizedScore(),
            score.itemsAnswered(), score.itemsCorrect(),
            coverage.getOrDefault(score.skillCode(), EvidenceCoverage.none())))
        .toList();
    return evidenceService.recordDiagnosticEvidence(
        attempt.learnerId(), attempt.id(), attempt.assessmentVersionId(),
        DiagnosticScorerV2.SCORING_VERSION, observations, interactionId);
  }

  private void recomputeMastery(
      AssessmentAttempt attempt, List<Evidence> evidence, String interactionId) {
    if (evidence.isEmpty()) {
      return;
    }
    UUID curriculumVersionId = repository.findCurriculumVersionId(attempt.assessmentVersionId())
        .orElseThrow(() -> new IllegalStateException(
            "No curriculum version for assessment version " + attempt.assessmentVersionId()));
    String traceId = CorrelationContext.currentTraceId();
    Set<UUID> affectedSkills = new LinkedHashSet<>();
    evidence.forEach(observation -> affectedSkills.add(observation.skillId()));
    for (UUID skillId : affectedSkills) {
      MasterySnapshot snapshot =
          masteryService.recompute(attempt.learnerId(), skillId, curriculumVersionId, interactionId);
      recommendationService.recommend(snapshot, interactionId, traceId);
    }
  }

  private void validateSelection(AssessmentItemScoringView view, List<String> selectedOptions) {
    AssessmentItemType type = AssessmentItemType.of(view.itemType());
    // Defence in depth. findPresentedItemScoringViews already filters to scoreable types, so a
    // SHORT_ANSWER or USE_CASE view should never reach this method -- if one somehow did, this is
    // where "fail closed" is meant to bite rather than let the scorer be asked to judge it.
    if (!type.scoreable()) {
      throw new InvalidSubmissionException(
          "Item " + view.itemVersionId() + " is type " + type + " and has no deterministic scorer.");
    }
    if (selectedOptions.size() != 1) {
      throw new InvalidSubmissionException(
          "Item " + view.itemVersionId() + " requires exactly one submitted answer.");
    }
    if (type == AssessmentItemType.SINGLE_CHOICE
        && !view.optionIds().contains(selectedOptions.getFirst())) {
      throw new InvalidSubmissionException(
          "Selected option is not valid for item " + view.itemVersionId() + ".");
    }
    // FILL_BLANK carries no option set to validate against; normalization and matching against the
    // accepted list happen in the scorer, which is also where an unmatched answer is decided wrong.
  }

  private SubmissionResult buildResult(AssessmentAttempt attempt) {
    List<ScoredResponse> scored = repository.findScoredResponses(attempt.id());
    return new SubmissionResult(attempt, scored.size(), scorer.aggregate(scored));
  }

  private String writeResponse(List<String> selectedOptions) {
    return objectMapper.writeValueAsString(Map.of("selectedOptions", selectedOptions));
  }

  private AssessmentAttempt withStatus(AssessmentAttempt attempt, String status) {
    return new AssessmentAttempt(
        attempt.id(), attempt.learnerId(), attempt.assessmentVersionId(),
        status, attempt.idempotencyKey(), attempt.createdAt(), attempt.updatedAt());
  }

  private UUID parseAttemptId(String rawAttemptId) {
    try {
      return UUID.fromString(rawAttemptId);
    } catch (IllegalArgumentException notAUuid) {
      throw new AttemptNotFoundException(rawAttemptId);
    }
  }

  private UUID parseItemId(String rawItemId) {
    try {
      return UUID.fromString(rawItemId);
    } catch (IllegalArgumentException notAUuid) {
      throw new UnknownAssessmentItemException(rawItemId);
    }
  }
}
