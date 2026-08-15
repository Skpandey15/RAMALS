package io.ramals.learningplatform.curriculum;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/curricula")
public class CurriculumController {

  private final CurriculumService service;

  public CurriculumController(CurriculumService service) {
    this.service = service;
  }

  @GetMapping("/{domainCode}/versions/{versionCode}/skills")
  @PreAuthorize("hasAnyRole('LEARNER', 'INSTRUCTOR', 'CONTENT_AUTHOR')")
  CurriculumGraph graph(@PathVariable String domainCode, @PathVariable String versionCode) {
    return service.graph(domainCode, versionCode);
  }

  @GetMapping("/{domainCode}/versions/{versionCode}/skills/{skillCode}/prerequisites")
  @PreAuthorize("hasAnyRole('LEARNER', 'INSTRUCTOR', 'CONTENT_AUTHOR')")
  List<String> prerequisites(
      @PathVariable String domainCode,
      @PathVariable String versionCode,
      @PathVariable String skillCode) {
    return service.graph(domainCode, versionCode).skills().stream()
        .filter(skill -> skill.stableCode().equals(skillCode))
        .findFirst()
        .orElseThrow(() -> new CurriculumNotFoundException(domainCode, versionCode + "/" + skillCode))
        .prerequisiteSkillCodes();
  }
}
