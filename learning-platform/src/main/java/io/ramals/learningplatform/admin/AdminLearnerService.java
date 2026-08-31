package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminLearnerService {

  private static final Set<String> ALLOWED_STATUS = Set.of("ACTIVE", "SUSPENDED", "CLOSED");

  private final AdminLearnerRepository learnerRepository;
  private final AdminActivityRepository auditRepository;

  public AdminLearnerService(
      AdminLearnerRepository learnerRepository, AdminActivityRepository auditRepository) {
    this.learnerRepository = learnerRepository;
    this.auditRepository = auditRepository;
  }

  public List<AdminLearnerSummary> listLearners() {
    return learnerRepository.findAll();
  }

  public AdminLearnerSummary getLearner(UUID learnerId) {
    return learnerRepository.findById(learnerId)
        .orElseThrow(() -> new AdminLearnerNotFoundException(learnerId));
  }

  @Transactional
  public AdminLearnerSummary changeStatus(String actorSubject, UUID learnerId, String requestedStatus) {
    String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
    if (!ALLOWED_STATUS.contains(status)) {
      throw new IllegalArgumentException("Unsupported learner status");
    }
    AdminLearnerSummary current = getLearner(learnerId);
    if (current.status().equals(status)) {
      return current;
    }
    if ("CLOSED".equals(current.status()) && !"CLOSED".equals(status)) {
      auditRepository.append(actorSubject, "CHANGE_LEARNER_STATUS", "LEARNER", learnerId,
          "REJECTED", "closed learner cannot be reactivated",
          CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
      throw new AdminLearnerStateConflictException("Closed learners cannot be reactivated");
    }
    int changed = learnerRepository.updateStatus(learnerId, status);
    if (changed != 1) {
      throw new AdminLearnerNotFoundException(learnerId);
    }
    auditRepository.appendWithinTransaction(actorSubject, "CHANGE_LEARNER_STATUS", "LEARNER", learnerId,
        "SUCCESS", current.status() + " -> " + status,
        CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
    return getLearner(learnerId);
  }
}
