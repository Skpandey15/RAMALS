package io.ramals.learningplatform.assessment;

/** Raised when a requested misconception id does not exist in {@code core.misconception} at all --
 * distinct from an existing misconception the learner simply has no eligible baseline for yet, which
 * is a valid 200 response ({@link LongitudinalDataStatus#NO_BASELINE}), never this exception. */
public class MisconceptionNotFoundException extends RuntimeException {

  public MisconceptionNotFoundException(String misconceptionId) {
    super("Misconception was not found: " + misconceptionId);
  }
}
