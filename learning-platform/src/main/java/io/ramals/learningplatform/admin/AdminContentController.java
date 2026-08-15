package io.ramals.learningplatform.admin;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role-gated content administration. Only curriculum lifecycle transitions are exposed; published
 * content cannot be edited in place. Every action is audited server-side with its interactionId.
 */
@RestController
@RequestMapping("/api/v1/admin/curricula")
@PreAuthorize("hasAnyRole('CONTENT_AUTHOR', 'ADMIN')")
public class AdminContentController {

  private final ContentAdminService service;

  public AdminContentController(ContentAdminService service) {
    this.service = service;
  }

  @GetMapping
  List<CurriculumVersionResponse> listCurricula() {
    return service.listCurricula().stream().map(CurriculumVersionResponse::from).toList();
  }

  @PostMapping("/{curriculumVersionId}/publish")
  CurriculumVersionResponse publish(
      Authentication authentication, @PathVariable String curriculumVersionId) {
    return CurriculumVersionResponse.from(
        service.publishCurriculum(authentication.getName(), curriculumVersionId));
  }

  @PostMapping("/{curriculumVersionId}/retire")
  CurriculumVersionResponse retire(
      Authentication authentication, @PathVariable String curriculumVersionId) {
    return CurriculumVersionResponse.from(
        service.retireCurriculum(authentication.getName(), curriculumVersionId));
  }
}
