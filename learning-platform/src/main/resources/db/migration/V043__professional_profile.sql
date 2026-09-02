-- The professional profile gate: PROFILE_PENDING -> JOURNEY_PENDING (M1-PROF-01 Doc 03 section 7).
--
-- Deliberately outside core.learner. M1-ADR-012 keeps that table operational and PII-free, and the
-- LLD states plainly that professional attributes are not added to it. This is the professional
-- boundary, keyed by learner so one learner holds at most one profile -- which is what makes a
-- resubmission an update rather than a second row, and idempotency a property of the schema rather
-- than of the code that happens to write it.
--
-- No migration is needed for the onboarding states themselves: V041's
-- ck_professional_onboarding_state already admits JOURNEY_PENDING and ONBOARDED. The states were
-- always legal; nothing ever wrote them.
--
-- Runtime grants are inherited. V041 set ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN
-- SCHEMA identity, so a table created here by that role grants SELECT/INSERT/UPDATE/DELETE to
-- ramals_core_runtime automatically. An explicit GRANT would be redundant, and a redundant GRANT is
-- indistinguishable from one that is load-bearing.

CREATE TABLE identity.professional_profile (
  learner_id UUID PRIMARY KEY REFERENCES core.learner(id) ON DELETE RESTRICT,

  -- Doc 03 recommends `current_role`, which PostgreSQL reserves: `CREATE TABLE t (current_role ...)`
  -- is a syntax error, and keeping the documented spelling would mean double-quoting the identifier
  -- at every call site forever. The suffix is the smaller cost.
  current_role_title VARCHAR(120) NOT NULL,
  experience_band VARCHAR(32) NOT NULL,
  primary_expertise VARCHAR(120) NOT NULL,

  -- Learner-declared and explicitly NON-AUTHORITATIVE (Doc 03 section 7). It shapes the first
  -- journey and is never an assessment result, so nothing downstream may read it as a measured
  -- proficiency. Nullable: declining to self-rate is a valid answer, and a forced guess is worse
  -- data than an absent one.
  declared_skill_level VARCHAR(32),

  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- Doc 03 names these fields but not their vocabularies. The values below are this
  -- implementation's proposal, constrained in the database rather than only in Java so that a
  -- direct write cannot introduce a band the application will not recognise.
  CONSTRAINT ck_professional_profile_experience_band CHECK (experience_band IN
    ('LESS_THAN_ONE_YEAR', 'ONE_TO_THREE_YEARS', 'THREE_TO_FIVE_YEARS',
     'FIVE_TO_TEN_YEARS', 'OVER_TEN_YEARS')),
  CONSTRAINT ck_professional_profile_declared_skill_level CHECK (
    declared_skill_level IS NULL OR declared_skill_level IN
      ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT')),

  -- Bounded, non-blank free text (Doc 03 section 14). NOT NULL alone would accept "   ".
  CONSTRAINT ck_professional_profile_role_present CHECK (length(btrim(current_role_title)) > 0),
  CONSTRAINT ck_professional_profile_expertise_present CHECK (length(btrim(primary_expertise)) > 0)
);
