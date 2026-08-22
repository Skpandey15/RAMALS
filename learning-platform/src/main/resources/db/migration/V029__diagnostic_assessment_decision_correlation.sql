-- M2-T09: correlate a gate decision with the interaction and trace it belongs to.
--
-- ledger.proposal_gate_decision already links to ai_execution through request_id and to the exact
-- retrieved context through context_id. What it could not answer was "which learner action, and
-- which distributed trace" -- questions an incident starts from, not ends with.
--
-- Additive and nullable by design. V028 rows predate the columns and are still valid decisions; a
-- NOT NULL column would have required inventing correlation identifiers for history that never
-- carried them, which is the opposite of preserving evidence.
ALTER TABLE ledger.proposal_gate_decision
  ADD COLUMN interaction_id VARCHAR(64),
  ADD COLUMN trace_id VARCHAR(64);

CREATE INDEX idx_proposal_gate_interaction
  ON ledger.proposal_gate_decision (interaction_id, decided_at DESC)
  WHERE interaction_id IS NOT NULL;

COMMENT ON COLUMN ledger.proposal_gate_decision.interaction_id IS
  'M2-T09 learner action this decision belongs to; NULL for decisions recorded before V029';
COMMENT ON COLUMN ledger.proposal_gate_decision.trace_id IS
  'M2-T09 W3C trace the decision was made under; NULL for decisions recorded before V029';
