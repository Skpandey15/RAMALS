package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for the learner-facing mastery map: the latest mastery score, evidence confidence, and
 * status per skill in a curriculum version, scoped to the authenticated learner.
 */
@Service
public class MasteryMapService {

  private final MasteryRepository masteryRepository;
  private final LearnerService learnerService;
  private final CurriculumService curriculumService;

  public MasteryMapService(
      MasteryRepository masteryRepository,
      LearnerService learnerService,
      CurriculumService curriculumService) {
    this.masteryRepository = masteryRepository;
    this.learnerService = learnerService;
    this.curriculumService = curriculumService;
  }

  @Transactional(readOnly = true)
  public List<MasteryMapEntry> masteryMap(String subject, String domainCode, String versionCode) {
    UUID curriculumVersionId = curriculumService.graph(domainCode, versionCode).curriculumVersionId();
    return learnerService.findLearner(subject)
        .map(Learner::id)
        .map(learnerId -> masteryRepository.latestMasteryMap(learnerId, curriculumVersionId))
        .orElseGet(List::of);
  }

  /** Admin/MCP equivalent: {@code learnerId} is already known and already authorized by the caller,
   * mirroring the same {@code ...ForLearner} convention {@code DiagnosticReportService}/{@code
   * LongitudinalEvidenceService} already use for their own callers that hold a learnerId rather than
   * an authenticated subject. A learner with no mastery record at all yields an empty list, the same
   * convention {@link #masteryMap} itself uses. */
  @Transactional(readOnly = true)
  public List<MasteryMapEntry> masteryMapForLearner(UUID learnerId, String domainCode, String versionCode) {
    UUID curriculumVersionId = curriculumService.graph(domainCode, versionCode).curriculumVersionId();
    return masteryRepository.latestMasteryMap(learnerId, curriculumVersionId);
  }
}
