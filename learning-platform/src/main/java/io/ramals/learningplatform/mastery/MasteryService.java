package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes deterministic mastery and evidence confidence for a learner and skill. Each recompute
 * takes the aggregate row lock, advances the monotonic aggregate version, and appends exactly one
 * snapshot for that version. Mastery is scored from evidence; confidence measures the sufficiency of
 * that evidence; the status policy then gates the claim of mastery on confidence and difficulty-band
 * coverage. Concurrent recomputes serialize into distinct versions with one canonical snapshot each.
 */
@Service
public class MasteryService {

  private static final Logger LOGGER = LoggerFactory.getLogger(MasteryService.class);

  private static final Set<String> OBSERVATION_TYPES =
      Set.of("DIAGNOSTIC", "QUIZ", "PRACTICE", "SCENARIO", "EVALUATION");

  private final MasteryRepository masteryRepository;
  private final EvidenceRepository evidenceRepository;
  private final WeightedMasteryCalculator masteryCalculator;
  private final EvidenceConfidenceCalculator confidenceCalculator;
  private final MasteryStatusPolicy statusPolicy;

  public MasteryService(
      MasteryRepository masteryRepository,
      EvidenceRepository evidenceRepository,
      WeightedMasteryCalculator masteryCalculator,
      EvidenceConfidenceCalculator confidenceCalculator,
      MasteryStatusPolicy statusPolicy) {
    this.masteryRepository = masteryRepository;
    this.evidenceRepository = evidenceRepository;
    this.masteryCalculator = masteryCalculator;
    this.confidenceCalculator = confidenceCalculator;
    this.statusPolicy = statusPolicy;
  }

  @Transactional
  public MasterySnapshot recompute(
      UUID learnerId, UUID skillId, UUID curriculumVersionId, String interactionId) {
    SkillMasteryConfig config = masteryRepository.findSkillConfig(skillId, curriculumVersionId)
        .orElseThrow(() -> new IllegalStateException(
            "No skill version configuration for skill " + skillId
                + " in curriculum version " + curriculumVersionId));

    masteryRepository.ensureAggregate(learnerId, skillId, curriculumVersionId);
    int nextVersion = masteryRepository.lockAggregateVersion(learnerId, skillId, curriculumVersionId) + 1;

    List<Evidence> evidence = evidenceRepository.findByLearnerAndSkill(learnerId, skillId);
    MasteryOutcome mastery =
        masteryCalculator.compute(evidence, config.masteryThreshold(), config.requiredEvidenceCount());
    ConfidenceOutcome confidence = confidenceCalculator.compute(confidenceInputs(evidence, config));

    MasteryStatus status = statusPolicy.refine(
        mastery.status(), confidence.confidence(), config.confidenceThreshold(),
        Set.copyOf(config.requiredDifficultyBands()), Set.of());

    MasterySnapshot snapshot = masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, curriculumVersionId, nextVersion, mastery.masteryScore(), status,
        mastery.threshold(), confidence.confidence(), config.confidenceThreshold(),
        mastery.evidenceCount(), mastery.itemsConsidered(),
        WeightedMasteryCalculator.ALGORITHM_VERSION, EvidenceConfidenceCalculator.ALGORITHM_VERSION,
        interactionId));
    masteryRepository.advanceAggregateVersion(learnerId, skillId, curriculumVersionId, nextVersion);
    BusinessEventLogger.info(LOGGER, "mastery.snapshot.calculated", "Mastery snapshot calculated",
        Map.of("entityType", "MASTERY_SNAPSHOT", "entityId", snapshot.id(),
            "learnerId", learnerId, "skillId", skillId, "stateTo", status,
            "aggregateVersion", nextVersion, "outcome", "SUCCESS"));
    return snapshot;
  }

  private ConfidenceInputs confidenceInputs(List<Evidence> evidence, SkillMasteryConfig config) {
    List<Evidence> observations = evidence.stream()
        .filter(item -> OBSERVATION_TYPES.contains(item.evidenceType()))
        .toList();
    int uniqueScoredItems = observations.stream().mapToInt(Evidence::itemsAnswered).sum();
    List<BigDecimal> normalizedScores = observations.stream().map(Evidence::normalizedScore).toList();
    long ageDays = observations.stream()
        .map(Evidence::occurredAt)
        .filter(java.util.Objects::nonNull)
        .max(Instant::compareTo)
        .map(latest -> Math.max(ChronoUnit.DAYS.between(latest, Instant.now()), 0))
        .orElse(0L);
    // MVP-0 evidence is not objective-tagged, so no required objective is credited as covered.
    return new ConfidenceInputs(
        uniqueScoredItems, config.requiredEvidenceCount(), 0, config.requiredObjectives(),
        ageDays, normalizedScores);
  }

  /**
   * Reads one snapshot by identity.
   *
   * <p>Exists so a caller that already knows which snapshot it produced can consume that one rather
   * than whatever is newest, which is not the same thing once a second learner event lands.
   */
  @Transactional(readOnly = true)
  public Optional<MasterySnapshot> snapshotById(UUID snapshotId) {
    return masteryRepository.findById(snapshotId);
  }

  @Transactional(readOnly = true)
  public Optional<MasterySnapshot> latestSnapshot(
      UUID learnerId, UUID skillId, UUID curriculumVersionId) {
    return masteryRepository.findLatestSnapshot(learnerId, skillId, curriculumVersionId);
  }
}
