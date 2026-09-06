package io.ramals.learningplatform.assessment;

/**
 * M2-ADR-029 (H6): which single level a {@link Misconception} targets -- mirrors the database's own
 * exclusive-arc {@code ck_misconception_target} (V057) for display purposes only. A misconception is
 * never structurally stored beneath its target; {@code targetType}/{@code targetId} on a report
 * finding are the only structural truth. Ancestry shown alongside them (objective/concept/sub-concept
 * context) is display context, not a claim about storage.
 */
public enum MisconceptionTargetType {

  /** {@link Misconception#targetObjectiveId()} is set; no {@link DiagnosticNode} is involved at
   * all. */
  LEARNING_OBJECTIVE,

  /** {@link Misconception#targetDiagnosticNodeId()} is set and that node's own
   * {@link DiagnosticNodeType} is {@code CONCEPT}. */
  CONCEPT,

  /** {@link Misconception#targetDiagnosticNodeId()} is set and that node's own
   * {@link DiagnosticNodeType} is {@code SUB_CONCEPT}. */
  SUB_CONCEPT
}
