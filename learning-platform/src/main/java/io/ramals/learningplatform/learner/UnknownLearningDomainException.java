package io.ramals.learningplatform.learner;

/** Raised when a goal references a learning domain that is not active or does not exist. */
public class UnknownLearningDomainException extends RuntimeException {

  public UnknownLearningDomainException(String domainCode) {
    super("Unknown or inactive learning domain: " + domainCode);
  }
}
