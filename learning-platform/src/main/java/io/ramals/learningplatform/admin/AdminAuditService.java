package io.ramals.learningplatform.admin;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {

  private final AdminAuditQueryRepository repository;

  public AdminAuditService(AdminAuditQueryRepository repository) {
    this.repository = repository;
  }

  public List<AdminAuditQueryRepository.AdminActivityView> recentAdminActivity(int limit) {
    return repository.recentAdminActivity(limit);
  }

  public List<AdminAuditQueryRepository.SecurityAuditView> recentSecurityActivity(int limit) {
    return repository.recentSecurityActivity(limit);
  }
}
