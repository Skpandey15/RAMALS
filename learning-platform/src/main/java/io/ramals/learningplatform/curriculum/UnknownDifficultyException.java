package io.ramals.learningplatform.curriculum;

/**
 * Raised when a difficulty or band value has no place in the curriculum vocabulary.
 *
 * <p>Thrown rather than defaulted. A value nobody mapped is a content or configuration mistake, and
 * the two things a default could do here are both wrong: silently crediting coverage the learner
 * never demonstrated, or silently withholding coverage they did. Failing closed makes the mistake
 * arrive at the write that introduced it.
 */
public class UnknownDifficultyException extends RuntimeException {

  public UnknownDifficultyException(String message) {
    super(message);
  }
}
