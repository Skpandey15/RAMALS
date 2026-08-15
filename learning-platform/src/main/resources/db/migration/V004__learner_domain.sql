-- Learner domain: server-authoritative learner identity mapped to the Keycloak
-- subject, plus a single active learning goal per learner. No personally
-- identifiable information is stored here; the opaque OIDC subject is the only
-- identity anchor, consistent with the Zero Trust data-minimization baseline.

CREATE FUNCTION core.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$;

CREATE TABLE core.learner (
  id UUID PRIMARY KEY,
  subject VARCHAR(255) NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_learner_subject_not_blank CHECK (length(btrim(subject)) > 0),
  CONSTRAINT ck_learner_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE core.learner IS 'Learner identity anchored to the Keycloak OIDC subject; contains no PII';
COMMENT ON COLUMN core.learner.subject IS 'Opaque Keycloak subject (sub) claim; the ownership boundary for all learner data';

CREATE TABLE core.learner_goal (
  learner_id UUID PRIMARY KEY REFERENCES core.learner(id) ON DELETE CASCADE,
  target_domain_id UUID NOT NULL REFERENCES core.learning_domain(id) ON DELETE RESTRICT,
  target_proficiency NUMERIC(5, 4) NOT NULL DEFAULT 0.8000,
  target_date DATE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_learner_goal_target CHECK (target_proficiency > 0 AND target_proficiency <= 1)
);

COMMENT ON TABLE core.learner_goal IS 'A learner''s single active learning goal (target domain and proficiency)';

CREATE INDEX idx_learner_goal_target_domain ON core.learner_goal(target_domain_id);

CREATE TRIGGER trg_learner_touch_updated_at
BEFORE UPDATE ON core.learner
FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_learner_goal_touch_updated_at
BEFORE UPDATE ON core.learner_goal
FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
