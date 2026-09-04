-- DIAGNOSTIC_SELECTION_V4: cross-attempt hypothesis confirmation.
--
-- The interviewer analogy this whole roadmap is built on has a piece V1-V3 do not have: when an
-- answer contradicts what the interviewer already believed about a candidate, a good interviewer
-- probes it before moving on. The current diagnostic model has no way to do that within one
-- attempt -- DiagnosticSubmissionService.submit takes a whole packet's worth of responses at once
-- and completes the attempt; there is no live "ask one more question because that answer was a
-- surprise" turn. Building that would be a genuine redesign of the submission API and the
-- learner-facing flow, not a selector change, and was deliberately not what this migration does.
--
-- What V4 does instead: when a learner's own mastery history shows an unexpected regression --
-- their most recent snapshot for a skill is a worse status than the one before it -- that skill is
-- prioritised for confirmation in their NEXT diagnostic attempt, with its own explicit reason. The
-- follow-up is deferred, not immediate, and that is the accepted trade-off for reusing the existing
-- one-shot-per-attempt model rather than rebuilding it.
--
-- Pure superset of V051's ten values: the DROP+ADD pair below only widens the membership check, so
-- the migration-compatibility checker accepts it as a rollback-safe widening on its own, the same
-- way V047, V050 and V051 did.
ALTER TABLE core.assessment_attempt_item DROP CONSTRAINT ck_assessment_attempt_item_reason;
ALTER TABLE core.assessment_attempt_item ADD CONSTRAINT ck_assessment_attempt_item_reason CHECK (
  selection_reason IN (
    'SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL',
    'UNSEEN_ITEM', 'LOW_CONFIDENCE', 'WEAK_SKILL', 'OBJECTIVE_COVERAGE_GAP',
    'DIFFICULTY_PROGRESSION', 'MASTERY_CONFIRMATION',
    'PREREQUISITE_NOT_SECURED',
    'HYPOTHESIS_CONFIRMATION'
  )
);

-- Deliberately no assessment_version row is updated here, same reasoning as V050/V051: which
-- selector a version declares is content-authoring and publication territory, not a side effect of
-- widening the reason vocabulary a new selector will need.
