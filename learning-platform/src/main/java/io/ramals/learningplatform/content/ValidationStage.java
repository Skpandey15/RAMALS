package io.ramals.learningplatform.content;

/**
 * The stages of the M1-ADR-006 pipeline, in the order they run.
 *
 * <p>Ordered cheapest-first, so a malformed item never consumes quality review. Declaration order is
 * execution order: {@code values()} is what the pipeline iterates, which means reordering the
 * pipeline means reordering this enum, in one place, visibly.
 *
 * <p>{@link #HUMAN_REVIEW} is in the set because a reviewer can reject too, and a rejection needs a
 * stage whatever refused it. It is not a stage the automated pipeline runs.
 */
public enum ValidationStage {

  /** Shape: required fields, option counts, well-formed answer key. */
  STRUCTURAL(true),

  /** Curriculum rules: the item's skill exists, the objective belongs to it, difficulty is legal. */
  DETERMINISTIC_POLICY(true),

  /** Content quality and safety: duplicates, answer-key sanity, disallowed material. */
  QUALITY_SAFETY(true),

  /** A person refused it. Never run by the automated pipeline. */
  HUMAN_REVIEW(false);

  private final boolean automated;

  ValidationStage(boolean automated) {
    this.automated = automated;
  }

  public boolean automated() {
    return automated;
  }
}
