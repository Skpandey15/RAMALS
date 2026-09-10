package io.ramals.learningplatform.assessment.misconceptiongraph;

/**
 * The requested {@link MisconceptionGraphTarget} resolves to no {@code core.learning_objective} or
 * {@code core.diagnostic_node} row of the requested kind.
 *
 * <p>Deterministic and distinct from a <em>valid</em> target that simply has no authored
 * misconception knowledge yet (M2-ADR-033 §14, prompt §14): the latter returns a successful empty
 * {@link MisconceptionGraphView}, this is an error. A kind mismatch -- e.g. a {@code SUB_CONCEPT}
 * row id requested as a {@code CONCEPT} -- is reported here too, never silently coerced.
 */
public class MisconceptionGraphTargetNotFoundException extends RuntimeException {

  public MisconceptionGraphTargetNotFoundException(MisconceptionGraphTarget target) {
    super("no " + target.kind() + " with id " + target.id());
  }
}
