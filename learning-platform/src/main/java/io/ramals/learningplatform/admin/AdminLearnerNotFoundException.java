package io.ramals.learningplatform.admin;

import java.util.UUID;

public class AdminLearnerNotFoundException extends RuntimeException {
  public AdminLearnerNotFoundException(UUID learnerId) {
    super("Learner not found: " + learnerId);
  }
}
