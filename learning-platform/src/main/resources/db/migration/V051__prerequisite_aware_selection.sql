-- DIAGNOSTIC_SELECTION_V3: prerequisite-aware selection, per M2-ADR-023.
--
-- V2's selector treats every skill as an independent track. It has no notion that KAFKA_TOPIC
-- depends on KAFKA_BROKER, even though core.skill_prerequisite has carried that edge since V003.
-- V3 adds exactly one thing on top of V2's already-frozen mechanics: when a skill's own evidence
-- would otherwise justify escalating past FOUNDATIONAL, but at least one of its curriculum
-- prerequisites has not reached MASTERED, the band is held at FOUNDATIONAL and the reason recorded
-- explicitly, so an audit trail can distinguish "escalated because it's earned" from "held back
-- because a dependency isn't secured yet" -- never a silent adjustment folded into an existing
-- reason's meaning.
--
-- M2-ADR-023 section 1 is explicit: this is evidence, never a gate. The skill is still selected --
-- only capped and deprioritised, never excluded -- which is why this needs only a new reason value
-- admitted into the same CHECK, not a new column or a new exclusion path.

-- Pure superset of V050's nine values: the DROP+ADD pair below only widens the membership check, so
-- the migration-compatibility checker accepts it as a rollback-safe widening on its own, the same
-- way V050 and V047 did.
ALTER TABLE core.assessment_attempt_item DROP CONSTRAINT ck_assessment_attempt_item_reason;
ALTER TABLE core.assessment_attempt_item ADD CONSTRAINT ck_assessment_attempt_item_reason CHECK (
  selection_reason IN (
    'SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL',
    'UNSEEN_ITEM', 'LOW_CONFIDENCE', 'WEAK_SKILL', 'OBJECTIVE_COVERAGE_GAP',
    'DIFFICULTY_PROGRESSION', 'MASTERY_CONFIRMATION',
    'PREREQUISITE_NOT_SECURED'
  )
);

-- Deliberately no assessment_version row is updated here. Which selector a version declares is
-- content-authoring and publication territory (V050's own comment), and no version -- v1 or the
-- Kafka v2 DRAFT bank -- is switched to DIAGNOSTIC_SELECTION_V3 by this migration. V3 exists and is
-- provable now; whether and when real content should run through it is a later, separate decision.
