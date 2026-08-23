-- M2-T09 review remediation: retain the safe parser-specific reason for proposals rejected before
-- a valid ProposalGroundingRequest exists. The public reason remains in reason_codes.

ALTER TABLE ledger.proposal_gate_decision
  ADD COLUMN parser_reason_code VARCHAR(64);

COMMENT ON COLUMN ledger.proposal_gate_decision.parser_reason_code IS
  'Stable parser reason for a pre-parse rejection; never raw model content or provider secrets';
