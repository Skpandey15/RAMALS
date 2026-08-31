package io.ramals.learningplatform.admin;

import org.springframework.stereotype.Service;

@Service
public class AdminOperationsService {

  private final AdminOperationsRepository repository;

  public AdminOperationsService(AdminOperationsRepository repository) {
    this.repository = repository;
  }

  public AdminOperationalSnapshot snapshot() {
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
