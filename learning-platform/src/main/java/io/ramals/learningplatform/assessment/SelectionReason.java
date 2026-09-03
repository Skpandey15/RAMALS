package io.ramals.learningplatform.assessment;

/**
 * Why an item earned its slot in a form.
 *
 * <p>Persisted per selected item so a form can be audited against the rule that produced it. The
 * set of items alone cannot distinguish a form that satisfied its coverage requirement from one
 * that happens to look as though it did.
 */
public enum SelectionReason {

  /** First item taken for its skill, satisfying the one-item-per-skill coverage floor. */
  SKILL_COVERAGE,

  /** Taken because its difficulty band was otherwise unrepresented in the form. */
  DIFFICULTY_COVERAGE,

  /** Taken to reach the configured form size once coverage was already satisfied. */
  FILL
}
