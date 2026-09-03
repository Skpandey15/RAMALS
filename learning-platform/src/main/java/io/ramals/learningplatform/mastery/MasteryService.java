package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
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
  private final EvidenceConfidenceCalculatorV2 confidenceCalculator;
  private final MasteryStatusPolicyV2 statusPolicy;

  public MasteryService(
      MasteryRepository masteryRepository,
      EvidenceRepository evidenceRepository,
      WeightedMasteryCalculator masteryCalculator,
      EvidenceConfidenceCalculatorV2 confidenceCalculator,
      MasteryStatusPolicyV2 statusPolicy) {
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

    // The two coverage facts V1 could not obtain, read from the evidence the learner has actually
    // produced. Both are scoped by the same query that scopes the mastery score itself -- this
    // learner, this skill -- so coverage can never be credited across a skill or a learner boundary.
    Set<UUID> requiredObjectives =
        masteryRepository.findRequiredObjectiveIds(skillId, curriculumVersionId);
    Set<UUID> coveredObjectives = coveredRequiredObjectives(evidence, requiredObjectives);
    Set<MasteryDifficultyBand> coveredBands = coveredDifficultyBands(evidence);

    ConfidenceOutcome confidence = confidenceCalculator.compute(
        confidenceInputs(evidence, config, requiredObjectives.size(), coveredObjectives.size()));

    MasteryStatus status = statusPolicy.refine(
        mastery.status(), confidence.confidence(), config.confidenceThreshold(),
        MasteryDifficultyBand.setOf(config.requiredDifficultyBands()), coveredBands);

    MasterySnapshot snapshot = masteryRepository.insertSnapshot(new MasterySnapshotDraft(
        learnerId, skillId, curriculumVersionId, nextVersion, mastery.masteryScore(), status,
        mastery.threshold(), confidence.confidence(), config.confidenceThreshold(),
        mastery.evidenceCount(), mastery.itemsConsidered(),
        WeightedMasteryCalculator.ALGORITHM_VERSION,
        EvidenceConfidenceCalculatorV2.ALGORITHM_VERSION, MasteryStatusPolicyV2.POLICY_VERSION,
        confidence.objectiveCoverage(), coveredBands,
        interactionId));
    masteryRepository.advanceAggregateVersion(learnerId, skillId, curriculumVersionId, nextVersion);
    BusinessEventLogger.info(LOGGER, "mastery.snapshot.calculated", "Mastery snapshot calculated",
        Map.of("entityType", "MASTERY_SNAPSHOT", "entityId", snapshot.id(),
            "learnerId", learnerId, "skillId", skillId, "stateTo", status,
            "aggregateVersion", nextVersion, "outcome", "SUCCESS"));
    return snapshot;
  }

  private ConfidenceInputs confidenceInputs(
      List<Evidence> evidence, SkillMasteryConfig config,
      int requiredObjectives, int coveredRequiredObjectives) {
    List<Evidence> observations = observations(evidence);
    int uniqueScoredItems = observations.stream().mapToInt(Evidence::itemsAnswered).sum();
    List<BigDecimal> normalizedScores = observations.stream().map(Evidence::normalizedScore).toList();
    long ageDays = observations.stream()
        .map(Evidence::occurredAt)
        .filter(java.util.Objects::nonNull)
        .max(Instant::compareTo)
        .map(latest -> Math.max(ChronoUnit.DAYS.between(latest, Instant.now()), 0))
        .orElse(0L);
    return new ConfidenceInputs(
        uniqueScoredItems, config.requiredEvidenceCount(),
        coveredRequiredObjectives, requiredObjectives, ageDays, normalizedScores);
  }

  /**
   * Which of the skill's required objectives this learner's evidence has actually measured.
   *
   * <p>Intersected with the required set rather than counted raw, so evidence tagged against an
   * objective the skill does not require cannot inflate coverage, and so repeated evidence against
   * one objective counts once however many times it is observed.
   */
  private Set<UUID> coveredRequiredObjectives(
      List<Evidence> evidence, Set<UUID> requiredObjectives) {
    Set<UUID> covered = new LinkedHashSet<>();
    for (Evidence observation : observations(evidence)) {
      for (UUID objectiveId : observation.coverage().objectiveIds()) {
        if (requiredObjectives.contains(objectiveId)) {
          covered.add(objectiveId);
        }
      }
    }
    return covered;
  }

  /** The bands this learner's evidence was measured at. Legacy rows contribute nothing. */
  private Set<MasteryDifficultyBand> coveredDifficultyBands(List<Evidence> evidence) {
    Set<MasteryDifficultyBand> covered = new LinkedHashSet<>();
    observations(evidence)
        .forEach(observation -> covered.addAll(observation.coverage().difficultyBands()));
    return covered;
  }

  /**
   * The evidence eligible to support a mastery claim: this learner's, this skill's (both already
   * enforced by the query), and an observation rather than a bookkeeping row. ADJUSTMENT evidence
   * restates a score and measures nothing, so it neither weighs into mastery nor grants coverage.
   */
  private List<Evidence> observations(List<Evidence> evidence) {
    return evidence.stream()
        .filter(item -> OBSERVATION_TYPES.contains(item.evidenceType()))
        .toList();
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
