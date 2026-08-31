package io.ramals.learningplatform.admin;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

  private static final int MAX_LIMIT = 200;
  private final AdminAuditService service;

  public AdminAuditController(AdminAuditService service) {
    this.service = service;
  }

  @GetMapping("/admin-activity")
  List<AdminAuditQueryRepository.AdminActivityView> adminActivity(
      @RequestParam(defaultValue = "50") int limit) {
    return service.recentAdminActivity(normalizeLimit(limit));
  }

  @GetMapping("/security")
  List<AdminAuditQueryRepository.SecurityAuditView> securityActivity(
      @RequestParam(defaultValue = "50") int limit) {
    return service.recentSecurityActivity(normalizeLimit(limit));
  }

  private int normalizeLimit(int limit) {
    if (limit < 1) {
      return 1;
    }
    return Math.min(limit, MAX_LIMIT);
  }
}
