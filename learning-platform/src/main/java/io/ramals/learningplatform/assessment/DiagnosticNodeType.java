package io.ramals.learningplatform.assessment;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): which of the two governed levels a
 * {@link DiagnosticNode} row represents. Exactly two levels exist; there is no third.
 */
public enum DiagnosticNodeType {

  /** Belongs directly to exactly one {@code core.learning_objective} -- a content-driven refinement
   * of what that objective bundles together. Never itself read as, or counted toward, an
   * objective's own coverage/mastery semantics. */
  CONCEPT,

  /** Belongs to exactly one {@link #CONCEPT} node -- one level finer, never nested further. A
   * {@code core.diagnostic_node} row can never be a SUB_CONCEPT of another SUB_CONCEPT; the
   * database enforces this (see V057's guard trigger), not just this enum's own shape. */
  SUB_CONCEPT
}
