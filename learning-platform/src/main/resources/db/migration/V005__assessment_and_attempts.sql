-- Assessment catalog and retry-safe diagnostic attempts.
--
-- Attempts pin the exact assessment_version (and, through it, the curriculum
-- version), so historical attempts stay reproducible after newer versions
-- publish. answer_key_jsonb lives only in the database and is never selected by
-- any learner-facing read path. Attempt creation is idempotent at the storage
-- layer: a scoped unique idempotency key collapses retries, and a partial unique
-- index enforces a single active attempt per learner and assessment version.

CREATE TABLE core.assessment (
  id UUID PRIMARY KEY,
  domain_id UUID NOT NULL REFERENCES core.learning_domain(id) ON DELETE RESTRICT,
  stable_code VARCHAR(96) NOT NULL,
  assessment_type VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (domain_id, stable_code),
  CONSTRAINT ck_assessment_stable_code CHECK (stable_code ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_assessment_type CHECK (assessment_type IN ('DIAGNOSTIC'))
);

CREATE TABLE core.assessment_version (
  id UUID PRIMARY KEY,
  assessment_id UUID NOT NULL REFERENCES core.assessment(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  version_code VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  published_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (assessment_id, version_code),
  CONSTRAINT ck_assessment_version_code CHECK (version_code ~ '^[a-z0-9][a-z0-9._-]*$'),
  CONSTRAINT ck_assessment_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
  CONSTRAINT ck_assessment_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL)
  )
);

CREATE TABLE core.assessment_item_version (
  id UUID PRIMARY KEY,
  assessment_version_id UUID NOT NULL REFERENCES core.assessment_version(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  item_code VARCHAR(96) NOT NULL,
  item_type VARCHAR(16) NOT NULL,
  stem TEXT NOT NULL,
  options_jsonb JSONB NOT NULL,
  answer_key_jsonb JSONB NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  display_order INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (assessment_version_id, item_code),
  UNIQUE (assessment_version_id, display_order),
  CONSTRAINT ck_assessment_item_code CHECK (item_code ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_assessment_item_type CHECK (item_type IN ('SINGLE_CHOICE')),
  CONSTRAINT ck_assessment_item_difficulty
    CHECK (difficulty IN ('FOUNDATIONAL', 'INTERMEDIATE', 'ADVANCED')),
  CONSTRAINT ck_assessment_item_display_order CHECK (display_order > 0),
  CONSTRAINT ck_assessment_item_options CHECK (
    jsonb_typeof(options_jsonb) = 'array' AND jsonb_array_length(options_jsonb) >= 2
  ),
  CONSTRAINT ck_assessment_item_answer_key CHECK (jsonb_typeof(answer_key_jsonb) = 'object')
);

COMMENT ON COLUMN core.assessment_item_version.answer_key_jsonb IS
  'Server-only correct-answer key; never selected by any learner-facing read path';

CREATE TABLE core.assessment_attempt (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  assessment_version_id UUID NOT NULL REFERENCES core.assessment_version(id) ON DELETE RESTRICT,
  status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
  idempotency_key VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_assessment_attempt_status
    CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ABANDONED')),
  CONSTRAINT ck_assessment_attempt_idempotency_key CHECK (length(btrim(idempotency_key)) > 0),
  CONSTRAINT uq_assessment_attempt_idempotency
    UNIQUE (learner_id, assessment_version_id, idempotency_key)
);

-- One active attempt per learner and assessment version.
CREATE UNIQUE INDEX uq_assessment_attempt_one_active
  ON core.assessment_attempt (learner_id, assessment_version_id)
  WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_assessment_attempt_learner
  ON core.assessment_attempt (learner_id, assessment_version_id, status, created_at);

CREATE INDEX idx_assessment_item_version_order
  ON core.assessment_item_version (assessment_version_id, display_order, id);

CREATE TRIGGER trg_assessment_attempt_touch_updated_at
BEFORE UPDATE ON core.assessment_attempt
FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- Published assessment content is immutable so pinned historical attempts remain
-- interpretable and answer keys cannot be altered after release.
CREATE FUNCTION core.validate_assessment_publication()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' THEN
    IF NOT EXISTS (
      SELECT 1 FROM core.assessment_item_version WHERE assessment_version_id = NEW.id
    ) THEN
      RAISE EXCEPTION 'assessment version % has no items', NEW.id USING ERRCODE = '23514';
    END IF;
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  ELSIF OLD.status IN ('PUBLISHED', 'RETIRED') THEN
    IF NOT (OLD.status = 'PUBLISHED' AND NEW.status = 'RETIRED'
        AND NEW.assessment_id = OLD.assessment_id
        AND NEW.curriculum_version_id = OLD.curriculum_version_id
        AND NEW.version_code = OLD.version_code
        AND NEW.published_at = OLD.published_at
        AND NEW.created_at = OLD.created_at) THEN
      RAISE EXCEPTION 'published assessment metadata is immutable' USING ERRCODE = '55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assessment_version_publication
BEFORE UPDATE ON core.assessment_version
FOR EACH ROW EXECUTE FUNCTION core.validate_assessment_publication();

CREATE FUNCTION core.protect_published_assessment_item()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  version_status VARCHAR(16);
  version_id UUID;
BEGIN
  version_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.assessment_version_id
                     ELSE NEW.assessment_version_id END;
  SELECT status INTO version_status FROM core.assessment_version WHERE id = version_id;
  IF version_status IN ('PUBLISHED', 'RETIRED') THEN
    RAISE EXCEPTION 'assessment version % is published and its items are immutable', version_id
      USING ERRCODE = '55000';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assessment_item_immutable
BEFORE INSERT OR UPDATE OR DELETE ON core.assessment_item_version
FOR EACH ROW EXECUTE FUNCTION core.protect_published_assessment_item();

-- Curated Kafka diagnostic, pinned to the published KAFKA v1 curriculum.
INSERT INTO core.assessment (id, domain_id, stable_code, assessment_type) VALUES
  ('01900000-0000-7000-8000-000000000401',
   '01900000-0000-7000-8000-000000000001', 'KAFKA_DIAGNOSTIC', 'DIAGNOSTIC');

INSERT INTO core.assessment_version (id, assessment_id, curriculum_version_id, version_code) VALUES
  ('01900000-0000-7000-8000-000000000402',
   '01900000-0000-7000-8000-000000000401',
   '01900000-0000-7000-8000-000000000002', 'v1');

INSERT INTO core.assessment_item_version (
  id, assessment_version_id, skill_id, item_code, item_type, stem,
  options_jsonb, answer_key_jsonb, difficulty, display_order
) VALUES
  ('01900000-0000-7000-8000-000000000411','01900000-0000-7000-8000-000000000402',
   '01900000-0000-7000-8000-000000000101','KAFKA_DIAG_BROKER','SINGLE_CHOICE',
   'Which responsibility belongs to a Kafka broker?',
   '[{"id":"A","text":"Rendering the consumer UI"},{"id":"B","text":"Storing partition log segments and serving fetch requests"},{"id":"C","text":"Compiling producer source code"},{"id":"D","text":"Assigning learner mastery levels"}]',
   '{"correct":["B"]}','FOUNDATIONAL',1),
  ('01900000-0000-7000-8000-000000000412','01900000-0000-7000-8000-000000000402',
   '01900000-0000-7000-8000-000000000102','KAFKA_DIAG_TOPIC','SINGLE_CHOICE',
   'A Kafka topic is best described as:',
   '[{"id":"A","text":"A single mutable database row"},{"id":"B","text":"A transient in-memory cache"},{"id":"C","text":"A durable, named, append-only stream of records"},{"id":"D","text":"A consumer thread pool"}]',
   '{"correct":["C"]}','FOUNDATIONAL',2),
  ('01900000-0000-7000-8000-000000000413','01900000-0000-7000-8000-000000000402',
   '01900000-0000-7000-8000-000000000103','KAFKA_DIAG_PARTITION','SINGLE_CHOICE',
   'Ordering in Kafka is guaranteed:',
   '[{"id":"A","text":"Across an entire topic"},{"id":"B","text":"Within a single partition"},{"id":"C","text":"Across a consumer group"},{"id":"D","text":"Only when acks=0"}]',
   '{"correct":["B"]}','INTERMEDIATE',3),
  ('01900000-0000-7000-8000-000000000414','01900000-0000-7000-8000-000000000402',
   '01900000-0000-7000-8000-000000000107','KAFKA_DIAG_ACKS','SINGLE_CHOICE',
   'Which producer acks setting gives the strongest durability?',
   '[{"id":"A","text":"acks=0"},{"id":"B","text":"acks=1"},{"id":"C","text":"acks=all"},{"id":"D","text":"acks=none"}]',
   '{"correct":["C"]}','INTERMEDIATE',4),
  ('01900000-0000-7000-8000-000000000415','01900000-0000-7000-8000-000000000402',
   '01900000-0000-7000-8000-000000000109','KAFKA_DIAG_CONSUMER_GROUPS','SINGLE_CHOICE',
   'Within one consumer group, a partition is consumed by:',
   '[{"id":"A","text":"Every consumer in the group"},{"id":"B","text":"Exactly one consumer at a time"},{"id":"C","text":"No consumer until rebalancing ends"},{"id":"D","text":"The broker leader"}]',
   '{"correct":["B"]}','INTERMEDIATE',5);

UPDATE core.assessment_version
SET status = 'PUBLISHED'
WHERE id = '01900000-0000-7000-8000-000000000402';
