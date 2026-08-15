-- Diagnostic responses. One immutable response per item per attempt. Responses may
-- only be appended while the attempt is IN_PROGRESS, which keeps submission
-- finalization single-shot: once an attempt is COMPLETED its responses are frozen,
-- so a duplicate submit can never append or alter recorded answers.

CREATE TABLE core.assessment_response (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  item_version_id UUID NOT NULL REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  response_jsonb JSONB NOT NULL,
  is_correct BOOLEAN NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (attempt_id, item_version_id),
  CONSTRAINT ck_assessment_response_shape CHECK (jsonb_typeof(response_jsonb) = 'object')
);

CREATE INDEX idx_assessment_response_attempt
  ON core.assessment_response (attempt_id, item_version_id);

CREATE FUNCTION core.protect_assessment_response()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  attempt_status VARCHAR(16);
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'assessment responses are immutable' USING ERRCODE = '55000';
  END IF;
  SELECT status INTO attempt_status FROM core.assessment_attempt WHERE id = NEW.attempt_id;
  IF attempt_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'responses may only be added to an in-progress attempt'
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assessment_response_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.assessment_response
FOR EACH ROW EXECUTE FUNCTION core.protect_assessment_response();
