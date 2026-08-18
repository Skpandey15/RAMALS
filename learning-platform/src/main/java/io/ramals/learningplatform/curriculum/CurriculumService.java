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

  public Optional<PublishedSkillContext> publishedSkillContext(String skillCode) {
    return skillCode == null || skillCode.isBlank()
        ? Optional.empty() : repository.findPublishedSkillContext(skillCode);
  }

  public boolean hasPublishedCurriculum(UUID domainId) {
    return repository.hasPublishedCurriculum(domainId);
  }
}
