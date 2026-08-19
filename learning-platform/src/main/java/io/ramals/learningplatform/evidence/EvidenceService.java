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
          observation.itemsAnswered(), observation.itemsCorrect(), provenance));
    }
    BusinessEventLogger.info(LOGGER, "evidence.recorded", "Diagnostic evidence recorded",
        Map.of("entityType", "EVIDENCE", "entityId", attemptId,
            "evidenceCount", recorded.size(), "outcome", "SUCCESS"));
    return recorded;
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
