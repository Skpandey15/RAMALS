package io.ramals.learningplatform.curriculum;

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
}
