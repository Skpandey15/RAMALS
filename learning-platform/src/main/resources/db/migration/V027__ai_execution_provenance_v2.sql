-- M2-T04 / M2-ADR-005 / M2-ADR-014: additive, rollback-compatible provenance v2.
-- Historical executions remain valid and honestly null; provenance is never reconstructed from a
-- route table that may have changed after the execution.
ALTER TABLE core.ai_execution
  ADD COLUMN resolved_provider VARCHAR(64),
  ADD COLUMN route_version VARCHAR(256),
  ADD COLUMN trace_id VARCHAR(64);

COMMENT ON COLUMN core.ai_execution.resolved_provider IS
  'Provider resolved by the governed AI route table; never caller supplied';
COMMENT ON COLUMN core.ai_execution.route_version IS
  'Immutable route-table/configuration stamp used for this execution';
COMMENT ON COLUMN core.ai_execution.trace_id IS
  'Trace correlation captured at the trusted Spring persistence boundary';
