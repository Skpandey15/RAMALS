package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes deterministic mastery for a learner and skill. Each recompute takes the aggregate row
 * lock, advances the monotonic aggregate version, and appends exactly one snapshot for that version.
 * Concurrent recomputes for the same learner-skill therefore serialize into distinct, increasing
 * versions with one canonical snapshot each; the same evidence at the same version always produces
 * the identical persisted score.
 */
@Service
public class MasteryService {

  private final MasteryRepository masteryRepository;
  private final EvidenceRepository evidenceRepository;
  private final WeightedMasteryCalculator calculator;

  public MasteryService(
      MasteryRepository masteryRepository,
      EvidenceRepository evidenceRepository,
      WeightedMasteryCalculator calculator) {
    this.masteryRepository = masteryRepository;
    this.evidenceRepository = evidenceRepository;
    this.calculator = calculator;
  }

  @Transactional
  public MasterySnapshot recompute(
      UUID learnerId, UUID skillId, UUID curriculumVersionId, String interactionId) {
    SkillMasteryConfig config = masteryRepository.findSkillConfig(skillId, curriculumVersionId)
        .orElseThrow(() -> new IllegalStateException(
            "No skill version configuration for skill " + skillId
                + " in curriculum version " + curriculumVersionId));

    masteryRepository.ensureAggregate(learnerId, skillId, curriculumVersionId);
    int currentVersion = masteryRepository.lockAggregateVersion(learnerId, skillId, curriculumVersionId);
    int nextVersion = currentVersion + 1;

    List<Evidence> evidence = evidenceRepository.findByLearnerAndSkill(learnerId, skillId);
    MasteryOutcome outcome =
        calculator.compute(evidence, config.masteryThreshold(), config.requiredEvidenceCount());

    MasterySnapshot snapshot = masteryRepository.insertSnapshot(
        learnerId, skillId, curriculumVersionId, nextVersion, outcome,
        WeightedMasteryCalculator.ALGORITHM_VERSION, interactionId);
    masteryRepository.advanceAggregateVersion(learnerId, skillId, curriculumVersionId, nextVersion);
    return snapshot;
  }

  @Transactional(readOnly = true)
  public Optional<MasterySnapshot> latestSnapshot(
      UUID learnerId, UUID skillId, UUID curriculumVersionId) {
    return masteryRepository.findLatestSnapshot(learnerId, skillId, curriculumVersionId);
  }
}
