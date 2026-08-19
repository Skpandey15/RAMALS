-- M1-T12 review outcomes are durable audit events, not only generic success/rejection events.

ALTER TABLE audit.admin_activity
  DROP CONSTRAINT ck_admin_activity_outcome;

ALTER TABLE audit.admin_activity
  ADD CONSTRAINT ck_admin_activity_outcome CHECK (
    outcome IN ('SUCCESS', 'REJECTED', 'APPROVED', 'SUPERSEDED', 'EXPIRED', 'CANCELLED')
  );

-- Keep existing installations aligned with the V019 runtime boundary.
GRANT SELECT, INSERT, UPDATE, DELETE
  ON TABLE core.assessment_approval_request, core.assessment_approval_command
  TO ramals_core_runtime;
