package io.ramals.learningplatform.learning;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives deterministic progression states for a learner over a curriculum version. Progression is a
 * read-only projection over the immutable mastery snapshots and the retention schedule; it never
 * mutates mastery history, so a regression changes only the derived state, never the record.
 */
@Service
public class ProgressionService {

  private final CurriculumService curriculumService;
  private final LearnerService learnerService;
  private final ProgressionRepository repository;
  private final ProgressionPolicy policy;

  public ProgressionService(
      CurriculumService curriculumService,
      LearnerService learnerService,
      ProgressionRepository repository,
      ProgressionPolicy policy) {
    this.curriculumService = curriculumService;
    this.learnerService = learnerService;
    this.repository = repository;
    this.policy = policy;
  }

  @Transactional(readOnly = true)
  public List<SkillProgression> progression(String subject, String domainCode, String versionCode) {
    CurriculumGraph graph = curriculumService.graph(domainCode, versionCode);
    UUID curriculumVersionId = graph.curriculumVersionId();

    Optional<UUID> learnerId = learnerService.findLearner(subject).map(Learner::id);
    Map<UUID, MasteryStatus> statuses = learnerId
        .map(id -> repository.latestStatuses(id, curriculumVersionId)).orElseGet(Map::of);
    Set<UUID> everMastered = learnerId
        .map(id -> repository.everMasteredSkillIds(id, curriculumVersionId)).orElseGet(Set::of);
    Set<UUID> retentionDue = learnerId
        .map(id -> repository.retentionDueSkillIds(id, curriculumVersionId, Instant.now()))
        .orElseGet(Set::of);

    Map<String, UUID> idByCode = graph.skills().stream()
        .collect(Collectors.toMap(CurriculumGraph.SkillNode::stableCode,
            CurriculumGraph.SkillNode::skillId));

    return graph.skills().stream().map(skill -> {
      MasteryStatus own = statuses.get(skill.skillId());
      List<UUID> prerequisiteIds = skill.prerequisiteSkillCodes().stream()
          .map(idByCode::get).toList();
      boolean prerequisitesReady = prerequisiteIds.stream()
          .allMatch(id -> statuses.get(id) == MasteryStatus.MASTERED);
      boolean anyPrerequisiteRegressed = prerequisiteIds.stream()
          .anyMatch(id -> statuses.get(id) != MasteryStatus.MASTERED && everMastered.contains(id));
      boolean retentionDueForSkill = retentionDue.contains(skill.skillId());

      ProgressionOutcome outcome = policy.decide(
          own, prerequisitesReady, retentionDueForSkill, anyPrerequisiteRegressed);
      return new SkillProgression(
          skill.skillId(), skill.stableCode(), outcome.state(), outcome.reasonCode(),
          own == null ? null : own.name());
    }).toList();
  }
}
