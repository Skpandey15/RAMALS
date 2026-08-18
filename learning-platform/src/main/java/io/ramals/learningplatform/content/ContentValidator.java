package io.ramals.learningplatform.content;

import java.util.Optional;

/**
 * One stage of the validation pipeline.
 *
 * <p>The method is {@code reject}, not {@code validate}, and returns a reason rather than a boolean.
 * That is deliberate: a validator's only power is refusal. There is no return value meaning
 * "approve", so no stage can quietly grow into one — the interface would have to change, visibly, in
 * review.
 */
public interface ContentValidator {

  ValidationStage stage();

  /**
   * @return a reason to refuse, or empty to let the content continue to the next stage. Empty is not
   *     approval; it is the absence of a reason to stop.
   */
  Optional<String> reject(CandidateContent candidate, ValidationContext context);
}
