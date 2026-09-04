package io.ramals.learningplatform.assessment;

/**
 * H4b foundation (M2-ADR-024): what happened when {@link ProbeRelationshipResolver} tried to turn a
 * trigger item into a servable probe. Deliberately five values, and deliberately distinct from
 * {@code AssessmentBankExhaustedException} -- that exception is {@code DiagnosticService}'s own
 * concept for the *whole selection pool* being exhausted; this is about one relationship's single
 * target objective, a narrower and different condition that must never be silently reported as the
 * same thing.
 */
public enum ProbeResolutionOutcome {

  /** No candidate target objective exists for this trigger under this relationship type at all --
   * no published {@code diagnostic_probe_relationship} row of this type from this source objective
   * (for {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK}), no curriculum prerequisite (for
   * {@code PREREQUISITE_VALIDATION}), or no other item shares the trigger's objective (for
   * {@code SAME_OBJECTIVE_CONFIRMATION}). No {@link DiagnosticHypothesis} is raised. */
  NO_RELATIONSHIP_DEFINED,

  /** More than one candidate target objective exists, and this foundation deliberately does not
   * rank or fan out across them -- see {@link ProbeRelationshipResolver}'s javadoc. Reachable from
   * more than one published {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK} relationship from
   * the same source objective (the schema's own uniqueness constraint, {@code (source_objective_id,
   * target_objective_id, relationship_type)}, permits exactly this), from a trigger skill with more
   * than one curriculum prerequisite or a prerequisite with more than one required objective, or
   * (surfaced earlier, as an exception rather than this outcome -- see
   * {@link TriggerItemHasAmbiguousObjectiveException}) a trigger item tagged to more than one
   * objective. No {@link DiagnosticHypothesis} is raised; every candidate that made the choice
   * ambiguous is reported instead, so the ambiguity itself is auditable rather than silently
   * resolved by whichever ordering a query happened to return first. */
  AMBIGUOUS_TARGET_OBJECTIVE,

  /** A target objective was found and a {@link DiagnosticHypothesis} was raised, but no verified,
   * scoreable item is tagged to it at all -- the relationship is real and published, the content to
   * serve it does not exist yet. */
  RELATIONSHIP_DEFINED_BUT_NO_ITEMS,

  /** A target objective has real items, but this learner has already been shown every one of them
   * (by logical identity, the same no-repeat exclusion every other selector already honours). */
  ALL_CANDIDATES_ALREADY_EXPOSED,

  /** At least one unseen, verified, scoreable item is available for the target objective. */
  CANDIDATES_AVAILABLE
}
