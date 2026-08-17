package io.ramals.learningplatform.content;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import java.util.Optional;

/**
 * The authoritative state a validator checks content against.
 *
 * <p>Passed per validation rather than injected once. Validators stay stateless, and — the reason
 * that matters — content is always checked against the curriculum as it is now, not against a graph
 * captured when the application started. A curriculum version can be retired between one validation
 * and the next, and content admitted against a withdrawn syllabus is content nobody checked.
 *
 * @param curriculum the published graph the content belongs to, absent when none is published
 */
public record ValidationContext(Optional<CurriculumGraph> curriculum) {

  public static ValidationContext of(CurriculumGraph curriculum) {
    return new ValidationContext(Optional.of(curriculum));
  }

  /** No published curriculum. Not a pass — validators treat it as a reason to refuse. */
  public static ValidationContext unavailable() {
    return new ValidationContext(Optional.empty());
  }
}
