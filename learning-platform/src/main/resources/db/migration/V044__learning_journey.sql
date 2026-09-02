-- The learning journey gate: JOURNEY_PENDING -> ONBOARDED (M1-PROF-01 Doc 03 section 8).
--
-- LearningJourney is the product-level orchestration model. core.learner_goal remains the
-- deterministic-core compatibility projection for MVP-1 and is NOT replaced here: Doc 03 section 8.5
-- puts its retirement behind a separate future ADR. One journey designates one primary domain, and
-- that domain is what projects into the existing one-goal-per-learner row.
--
-- Deviation from Doc 03's field list, agreed before implementation: target_proficiency and
-- target_date live here too. core.learner_goal.target_proficiency is NOT NULL, so the projection
-- cannot be written without a value, and Doc 03's journey model has no field that yields one. The
-- alternatives were deriving it from learning_intensity or defaulting it -- both of which would put
-- an undocumented rule, or a magic number, inside the deterministic core. The learner supplies it,
-- exactly as the existing PUT /me/goal contract already has them do, with the same (0,1] bound.
--
-- Additional selected domains (Doc 03: "Selected domains use child rows") are deliberately NOT
-- modelled yet. Nothing in the JOURNEY_PENDING -> ONBOARDED transition reads them, and a child table
-- with no writer and no reader is a schema that documents an intention rather than a behaviour.

CREATE TABLE identity.learning_journey (
  id UUID PRIMARY KEY,

  -- One journey per learner in MVP-1. UNIQUE rather than merely indexed: it is what makes a repeated
  -- submission an update of the same journey instead of a second one silently shadowing the first,
  -- and it keeps the one-journey-to-one-goal mapping true by construction rather than by convention.
  learner_id UUID NOT NULL UNIQUE REFERENCES core.learner(id) ON DELETE RESTRICT,

  goal_type VARCHAR(32) NOT NULL,
  target_role VARCHAR(120) NOT NULL,
  learning_intensity VARCHAR(32) NOT NULL,
  weekly_hours INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL,

  -- The designated primary domain. RESTRICT, not CASCADE: removing a catalog domain must not
  -- silently delete the journeys built on it, nor orphan the goal projected from it.
  primary_domain_id UUID NOT NULL REFERENCES core.learning_domain(id) ON DELETE RESTRICT,

  -- Projected into core.learner_goal. Bounds mirror LearnerGoalRequest so the journey cannot store a
  -- value the legacy goal contract would have rejected -- one field, one meaning, two writers.
  target_proficiency NUMERIC(4, 3) NOT NULL,
  target_date DATE,

  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- Doc 03 names these fields but not their vocabularies. The values are this implementation's
  -- proposal, constrained in the database as well as in Java so a direct write cannot introduce one
  -- the application will not recognise.
  CONSTRAINT ck_learning_journey_goal_type CHECK (goal_type IN
    ('ROLE_TRANSITION', 'DEPTH_IN_CURRENT_ROLE', 'CERTIFICATION', 'EXPLORATION')),
  CONSTRAINT ck_learning_journey_intensity CHECK (learning_intensity IN
    ('CASUAL', 'STEADY', 'INTENSIVE')),
  CONSTRAINT ck_learning_journey_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'ABANDONED')),

  -- Bounded weekly hours (Doc 03 section 14). An unbounded integer here would be projected into
  -- planning as a commitment nobody can meet.
  CONSTRAINT ck_learning_journey_weekly_hours CHECK (weekly_hours BETWEEN 1 AND 40),

  -- Same bound as LearnerGoalRequest: greater than zero, at most one.
  CONSTRAINT ck_learning_journey_proficiency CHECK (
    target_proficiency > 0 AND target_proficiency <= 1),

  CONSTRAINT ck_learning_journey_target_role_present CHECK (length(btrim(target_role)) > 0)
);
