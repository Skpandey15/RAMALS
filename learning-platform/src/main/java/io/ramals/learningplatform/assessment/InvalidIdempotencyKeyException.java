package io.ramals.learningplatform.assessment;

/** Raised when a critical write is missing a usable Idempotency-Key header. */
public class InvalidIdempotencyKeyException extends RuntimeException {

  public InvalidIdempotencyKeyException(String reason) {
    super(reason);
  }
}
