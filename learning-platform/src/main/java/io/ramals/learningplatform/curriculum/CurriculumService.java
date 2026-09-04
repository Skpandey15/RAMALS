package io.ramals.learningplatform.curriculum;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CurriculumService {

  private final CurriculumRepository repository;
  private final CurriculumGraphValidator validator;

  public CurriculumService(CurriculumRepository repository, CurriculumGraphValidator validator) {
    this.repository = repository;
    this.validator = validator;
  }

  public CurriculumGraph graph(String domainCode, String versionCode) {
    CurriculumGraph graph = repository.findReadableGraph(domainCode, versionCode)
        .orElseThrow(() -> new CurriculumNotFoundException(domainCode, versionCode));
    validator.validate(graph);
    return graph;
  }

  /**
   * Resolves a graph by the curriculum version's own id, for a caller that already holds
   * {@code assessment_version.curriculum_version_id} rather than a domain code and the
   * curriculum's own version code. Failure here means an assessment version's FK points at a
   * curriculum version that does not exist or is not readable -- a structural inconsistency, not
   * a learner-facing "not found", hence {@link IllegalStateException} rather than
   * {@link CurriculumNotFoundException}.
   */
  public CurriculumGraph graph(UUID curriculumVersionId) {
    CurriculumGraph graph = repository.findReadableGraph(curriculumVersionId)
        .orElseThrow(() -> new IllegalStateException(
            "No readable curriculum graph for curriculum version: " + curriculumVersionId));
    validator.validate(graph);
    return graph;
  }

  public Optional<PublishedSkillContext> publishedSkillContext(String skillCode) {
    return skillCode == null || skillCode.isBlank()
        ? Optional.empty() : repository.findPublishedSkillContext(skillCode);
  }

  public boolean hasPublishedCurriculum(UUID domainId) {
    return repository.hasPublishedCurriculum(domainId);
  }
}
