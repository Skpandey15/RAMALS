package io.ramals.learningplatform.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/learners")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLearnerController {

  private final AdminLearnerService service;

  public AdminLearnerController(AdminLearnerService service) {
    this.service = service;
  }

  @GetMapping
  List<AdminLearnerSummary> listLearners() {
    return service.listLearners();
  }

  @GetMapping("/{learnerId}")
  AdminLearnerSummary getLearner(@PathVariable UUID learnerId) {
    return service.getLearner(learnerId);
  }

  @PatchMapping("/{learnerId}/status")
  @PreAuthorize("hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication)")
  AdminLearnerSummary changeStatus(
      Authentication authentication,
      @PathVariable UUID learnerId,
      @RequestBody StatusRequest request) {
    return service.changeStatus(authentication.getName(), learnerId, request.status());
  }

  public record StatusRequest(String status) {}
}
