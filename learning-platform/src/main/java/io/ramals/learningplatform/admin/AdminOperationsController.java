package io.ramals.learningplatform.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOperationsController {

  private final AdminOperationsService service;

  public AdminOperationsController(AdminOperationsService service) {
    this.service = service;
  }

  @GetMapping("/snapshot")
  AdminOperationalSnapshot snapshot() {
    return service.snapshot();
  }
}
