package io.ramals.learningplatform.diagnosis;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasteryMapEntry;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explains why a skill reads weak, using nothing the mastery engine has not already authoritatively
 * computed: this learner's latest {@link MasteryStatus} per skill, and the curriculum's prerequisite
 * graph. See M2-ADR-023.
 *
 * <p><b>Read-only, by construction.</b> This class has no writer dependency at all -- not
 * {@code EvidenceRepository}, not {@code MasteryRepository}'s snapshot-writing methods, nothing that
 * could turn an interpretation into a mutation. It never re-derives a mastery score, a confidence
 * value, or a threshold comparison; {@link MasteryStatus} is consumed exactly as the mastery engine
 * already decided it, the same discipline {@code SkillMasterySignal} holds in the {@code assessment}
 * package. The classification logic itself lives in {@link GapDiagnosisClassifier}, a pure function
 * of a graph and a status map -- this class only resolves those two inputs from the database.
 *
 * <p><b>"Secured" means MASTERED, nothing weaker.</b> A prerequisite that is merely DEVELOPING is
 * still treated as weak by the classifier -- reusing the mastery engine's own bar for "secure"
 * rather than inventing a second one.
 */
@Service
public class GapDiagnosisService {

  private final CurriculumService curriculumService;
  private final MasteryRepository masteryRepository;
  private final LearnerService learnerService;

  public GapDiagnosisService(
      CurriculumService curriculumService,
      MasteryRepository masteryRepository,
      LearnerService learnerService) {
    this.curriculumService = curriculumService;
    this.masteryRepository = masteryRepository;
    this.learnerService = learnerService;
  }

  /**
   * Diagnoses every skill in the given curriculum version for the authenticated learner. A learner
   * with no record at all yields an empty report -- the same convention
   * {@code MasteryMapService.masteryMap} already uses -- rather than a report full of manufactured
   * INSUFFICIENT_EVIDENCE rows for a learner who does not yet exist.
   */
  @Transactional(readOnly = true)
  public GapDiagnosisReport diagnose(String subject, String domainCode, String versionCode) {
    CurriculumGraph graph = curriculumService.graph(domainCode, versionCode);
    return learnerService.findLearner(subject)
        .map(learner -> diagnoseFor(learner, graph))
        .orElseGet(() -> new GapDiagnosisReport(null, graph.curriculumVersionId(), List.of()));
  }

  private GapDiagnosisReport diagnoseFor(Learner learner, CurriculumGraph graph) {
    Map<String, MasteryStatus> statusByCode = new LinkedHashMap<>();
    for (MasteryMapEntry entry
        : masteryRepository.latestMasteryMap(learner.id(), graph.curriculumVersionId())) {
      statusByCode.put(entry.skillCode(), MasteryStatus.valueOf(entry.masteryStatus()));
    }
    return new GapDiagnosisReport(
        learner.id(), graph.curriculumVersionId(), GapDiagnosisClassifier.classifyAll(graph, statusByCode));
  }
}
