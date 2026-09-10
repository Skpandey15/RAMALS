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

  /**
   * The authoritative domain facts for a domain code, for a caller that holds a domain rather than
   * a skill (the diagnostic-probe recommendation path, whose H6/H7 evidence is domain-scoped).
   * Empty when the code is null, blank, or unknown.
   */
  public Optional<PublishedDomainContext> publishedDomainContext(String domainCode) {
    return domainCode == null || domainCode.isBlank()
        ? Optional.empty() : repository.findPublishedDomainContext(domainCode);
  }

  public boolean hasPublishedCurriculum(UUID domainId) {
    return repository.hasPublishedCurriculum(domainId);
  }

  /** The domain a skill structurally belongs to. Empty when the skill id is unknown. */
  public Optional<String> domainCodeForSkill(UUID skillId) {
    return repository.findDomainCodeForSkill(skillId);
  }
}
