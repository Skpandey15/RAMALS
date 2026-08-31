package io.ramals.learningplatform.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOperationsController {

  private final AdminOperationsRepository repository;

  public AdminOperationsController(AdminOperationsRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/snapshot")
  AdminOperationalSnapshot snapshot() {
    return new AdminOperationalSnapshot(
        repository.countLearners(),
        repository.countLearners("ACTIVE"),
        repository.countLearners("SUSPENDED"),
        repository.countLearners("CLOSED"),
        repository.countOnboarded(),
        repository.countCurricula("DRAFT"),
        repository.countCurricula("PUBLISHED"),
        repository.countCurricula("RETIRED"),
        repository.countAuthorizationDenials24h(),
        repository.countAdminActions24h());
  }
}
