package io.ramals.learningplatform.admin;

/** Raised when a lifecycle command is not valid for a content version's current status. */
public class InvalidContentTransitionException extends RuntimeException {

  public InvalidContentTransitionException(String action, String status) {
    super(action + " is not valid for a version in status " + status + ".");
  }
}
