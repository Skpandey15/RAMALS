-- M2-ADR-032 (step 2): immutable audit for the advisory diagnostic-probe proposal gate.
--
-- An agent that has read governed H6/H7 evidence through MCP may propose ONE bounded next
-- diagnostic-probe candidate per interaction. It asserts nothing about the learner; Java's
-- deterministic, versioned, fail-closed gate (DiagnosticProbeProposalGate, DIAGNOSTIC_PROBE_
-- PROPOSAL_GATE_V1) independently decides whether it has any effect. This table records every such
-- decision -- accepted or rejected, and malformed payloads too -- with enough identity to answer
-- M2-ADR-032 19's audit questions, and nothing more.
--
-- No probe is activated by anything here. Acceptance means "well-formed, evidence-grounded, in
-- scope", never "eligible" or "will run": eligibility stays with DIAGNOSTIC_SELECTION_V1-V5,
-- untouched (M2-ADR-032 6/13). This migration changes no existing table and no existing grant.
--
-- Append-only, like every ledger.* audit table: the shared ledger.reject_grounding_audit_mutation()
-- guard (V028) rejects UPDATE and DELETE. Idempotent by (proposal_id, policy_version), the same key
-- ledger.proposal_gate_decision already uses, so a retried evaluation of the identical proposal
-- collapses to one row. No prompt, no model output, no chain-of-thought column (M2-ADR-005,
-- unchanged).

CREATE TABLE ledger.diagnostic_probe_proposal_decision (
  id UUID PRIMARY KEY,
  proposal_id VARCHAR(64) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  agent_run_id VARCHAR(64),
  -- The learner action this decision belongs to, and the distributed trace it was made under
  -- (M2-ADR-032 19). interaction_id is authoritative context, always present; trace_id is nullable
  -- for the same reason V029 made it nullable on ledger.proposal_gate_decision.
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  -- Derived solely from the verified M2-ADR-031 delegated context; the proposal carries no learner
  -- field and could not override this if it did.
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  domain_code VARCHAR(64) NOT NULL,
  proposal_contract_version VARCHAR(16) NOT NULL,
  policy_version VARCHAR(64) NOT NULL,
  accepted BOOLEAN NOT NULL,
  reason_codes JSONB NOT NULL,
  -- Set only when the payload could not be read as the v1 contract at all.
  parser_reason_code VARCHAR(64),
  -- What the proposal referenced (NULL when it was malformed and nothing could be parsed).
  target_misconception_id UUID,
  target_node_kind VARCHAR(20),
  target_node_id UUID,
  probe_intent VARCHAR(48),
  candidate_probe_ref UUID,
  -- E_allowed and E_proposed (M2-ADR-032 11/19). Stored as sorted JSON arrays of opaque strings.
  allowed_evidence_refs JSONB NOT NULL,
  cited_evidence_refs JSONB NOT NULL,
  -- Provenance of the producing execution (M2-ADR-032 19). All nullable, mirroring AiProposalEnvelope.
  prompt_template_id VARCHAR(64),
  prompt_version VARCHAR(64),
  model_route VARCHAR(64),
  resolved_provider VARCHAR(64),
  model_id VARCHAR(128),
  route_version VARCHAR(64),
  decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_diagnostic_probe_proposal_decision UNIQUE (proposal_id, policy_version),
  CONSTRAINT ck_diagnostic_probe_proposal_decision_reasons CHECK (
    jsonb_typeof(reason_codes) = 'array' AND jsonb_array_length(reason_codes) > 0),
  -- accepted iff the only reason is ACCEPTED; a rejected decision always names at least one reason
  -- and never claims acceptance.
  CONSTRAINT ck_diagnostic_probe_proposal_decision_accepted CHECK (
    accepted = (reason_codes = '["ACCEPTED"]'::jsonb)),
  CONSTRAINT ck_diagnostic_probe_proposal_decision_evidence CHECK (
    jsonb_typeof(allowed_evidence_refs) = 'array'
    AND jsonb_typeof(cited_evidence_refs) = 'array'),
  CONSTRAINT ck_diagnostic_probe_proposal_decision_trace CHECK (
    trace_id IS NULL OR length(btrim(trace_id)) BETWEEN 1 AND 64),
  CONSTRAINT ck_diagnostic_probe_proposal_decision_node CHECK (
    (target_node_kind IS NULL) = (target_node_id IS NULL)),
  CONSTRAINT ck_diagnostic_probe_proposal_decision_node_kind CHECK (
    target_node_kind IS NULL
    OR target_node_kind IN ('LEARNING_OBJECTIVE', 'CONCEPT', 'SUB_CONCEPT')),
  CONSTRAINT ck_diagnostic_probe_proposal_decision_contract_version CHECK (
    length(btrim(proposal_contract_version)) BETWEEN 1 AND 16),
  CONSTRAINT ck_diagnostic_probe_proposal_decision_policy_version CHECK (
    length(btrim(policy_version)) BETWEEN 1 AND 64)
);

CREATE INDEX idx_diagnostic_probe_proposal_decision_interaction
  ON ledger.diagnostic_probe_proposal_decision (interaction_id, decided_at DESC);

CREATE INDEX idx_diagnostic_probe_proposal_decision_learner
  ON ledger.diagnostic_probe_proposal_decision (learner_id, decided_at DESC);

CREATE TRIGGER trg_diagnostic_probe_proposal_decision_append_only
BEFORE UPDATE OR DELETE ON ledger.diagnostic_probe_proposal_decision
FOR EACH ROW EXECUTE FUNCTION ledger.reject_grounding_audit_mutation();

COMMENT ON TABLE ledger.diagnostic_probe_proposal_decision IS
  'M2-ADR-032 immutable deterministic accept/reject for advisory diagnostic-probe proposals; '
  'stable reason codes, no prompts or model output, no probe activation';
