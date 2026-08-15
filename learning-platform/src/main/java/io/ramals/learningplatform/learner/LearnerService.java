package io.ramals.learningplatform.learner;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learner context is derived exclusively from the authenticated Keycloak subject, never from
 * client-supplied identifiers, so a learner can only ever act on their own data.
 */
@Service
public class LearnerService {

  private final LearnerRepository repository;

  public LearnerService(LearnerRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public Learner currentLearner(String subject) {
    return repository.provisionForSubject(subject);
  }

  @Transactional
  public LearnerGoal currentGoal(String subject) {
    Learner learner = repository.provisionForSubject(subject);
    return repository.findGoal(learner.id()).orElseThrow(LearnerGoalNotSetException::new);
  }

  @Transactional
  public LearnerGoal setGoal(String subject, LearnerGoalRequest request) {
    Learner learner = repository.provisionForSubject(subject);
    UUID targetDomainId = repository.findActiveDomainId(request.targetDomainCode())
        .orElseThrow(() -> new UnknownLearningDomainException(request.targetDomainCode()));
    return repository.upsertGoal(
        learner.id(), targetDomainId, request.targetProficiency(), request.targetDate());
  }
}
