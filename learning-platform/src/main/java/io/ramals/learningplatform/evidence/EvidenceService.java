package io.ramals.learningplatform.evidence;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends immutable evidence. Every write is idempotent on its source-lineage key, so retries and
 * duplicate submissions reuse the original evidence instead of duplicating it. Corrections are
 * expressed as adjustment evidence that references the superseded row; nothing is ever rewritten.
 */
@Service
public class EvidenceService {

  private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceService.class);

  private final EvidenceRepository repository;

  public EvidenceService(EvidenceRepository repository) {
    this.repository = repository;
  }

  /** Records one diagnostic evidence row per scored skill, tagged with its interactionId. */
  @Transactional
  public List<Evidence> recordDiagnosticEvidence(
      UUID learnerId,
      UUID attemptId,
      UUID assessmentVersionId,
      String scoringVersion,
      List<SkillEvidenceInput> observations,
      String interactionId) {
    String provenance = requireInteractionId(interactionId);
    Map<String, UUID> skillIds = repository.resolveAttemptSkills(attemptId);

    List<Evidence> recorded = new ArrayList<>();
    for (SkillEvidenceInput observation : observations) {
      UUID skillId = skillIds.get(observation.skillCode());
      if (skillId == null) {
        throw new IllegalStateException(
            "No skill id resolved for evidence skill code: " + observation.skillCode());
      }
      String lineageKey = "ASSESSMENT_ATTEMPT:" + attemptId + ":SKILL:" + skillId;
      recorded.add(repository.appendDiagnosticEvidence(
          learnerId, skillId, attemptId, assessmentVersionId, scoringVersion, lineageKey,
          observation.observedScore(), observation.normalizedScore(),
          observation.itemsAnswered(), observation.itemsCorrect(), observation.coverage(),
          provenance));
    }
    BusinessEventLogger.info(LOGGER, "evidence.recorded", "Diagnostic evidence recorded",
        Map.of("entityType", "EVIDENCE", "entityId", attemptId,
            "evidenceCount", recorded.size(), "outcome", "SUCCESS"));
    return recorded;
  }

  /**
   * Records one evidence row for an accepted rubric evaluation (M2-T14).
   *
   * <p>The lineage key is the gated evaluation's request identity, so a replayed workflow trigger
   * collapses onto the original row. That is the only thing standing between an at-least-once
   * orchestration trigger and a learner whose mastery climbs every time a message is redelivered.
   *
   * <p>The caller supplies the skill and attempt because they are Spring-owned facts about what was
   * assessed. Nothing here is read from the proposal: the gate decided the evaluation was
   * acceptable, it did not get to decide whose evidence it becomes.
   */
  @Transactional
  public Evidence recordEvaluationEvidence(
      UUID learnerId,
      UUID skillId,
      UUID attemptId,
      UUID assessmentVersionId,
      String evaluationRequestId,
      String scoringVersion,
      BigDecimal normalizedScore,
      String interactionId) {
    String provenance = requireInteractionId(interactionId);
    if (evaluationRequestId == null || evaluationRequestId.isBlank()) {
      throw new IllegalArgumentException("Evaluation evidence requires its gated request identity.");
    }
    if (normalizedScore == null
        || normalizedScore.signum() < 0
        || normalizedScore.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("Evaluation evidence requires a normalized score in [0,1].");
    }
    String lineageKey = "EVALUATION_DECISION:" + evaluationRequestId + ":SKILL:" + skillId;
    Evidence evidence = repository.appendEvaluationEvidence(
        learnerId, skillId, attemptId, assessmentVersionId, scoringVersion, lineageKey,
        // One answer was scored, and "correct" is not a meaningful binary for a rubric: the whole
        // signal lives in the normalized score. Claiming a correct item here would inflate the
        // confidence calculator's item counts with a judgement the rubric never made.
        // A rubric evaluation measures no catalogued item, so it carries no objective or band
        // coverage. Claiming coverage here would credit breadth that nothing in the ledger shows.
        normalizedScore, normalizedScore, 1, 0, EvidenceCoverage.none(), provenance);
    BusinessEventLogger.info(LOGGER, "evidence.recorded", "Evaluation evidence recorded",
        Map.of("entityType", "EVIDENCE", "entityId", evidence.id(),
            "evaluationRequestId", evaluationRequestId, "outcome", "SUCCESS"));
    return evidence;
  }

  /**
   * Appends adjustment evidence that supersedes {@code originalEvidenceId}. Idempotent per
   * (original, reasonKey), so a retried correction reuses the same adjustment.
   */
  @Transactional
  public Evidence appendAdjustment(
      UUID originalEvidenceId,
      BigDecimal observedScore,
      BigDecimal normalizedScore,
      String reasonKey,
      String interactionId) {
    String provenance = requireInteractionId(interactionId);
    Evidence original = repository.findById(originalEvidenceId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Cannot adjust unknown evidence: " + originalEvidenceId));
    String lineageKey = "ADJUSTMENT:" + originalEvidenceId + ":" + reasonKey;
    Evidence adjustment = repository.appendAdjustmentEvidence(
        original.learnerId(), original.skillId(), originalEvidenceId, lineageKey,
        observedScore, normalizedScore, provenance);
    BusinessEventLogger.info(LOGGER, "evidence.recorded", "Evidence adjustment recorded",
        Map.of("entityType", "EVIDENCE", "entityId", adjustment.id(),
            "supersedesEvidenceId", originalEvidenceId, "outcome", "SUCCESS"));
    return adjustment;
  }

  private String requireInteractionId(String interactionId) {
    if (interactionId == null || interactionId.isBlank()) {
      throw new IllegalStateException("Evidence requires an interactionId for provenance.");
    }
    return interactionId;
  }
}
