package io.ramals.learningplatform.grounding;

/** Stable, observable reason codes; never model-authored prose. */
public enum ProposalGateReason {
  ACCEPTED,
  PROPOSAL_INVALID,
  PROPOSAL_VERSION_UNSUPPORTED,
  CONTEXT_ID_MISMATCH,
  GROUNDING_INVALID,
  CLAIM_UNSUPPORTED,
  EVIDENCE_REFERENCE_UNKNOWN,
  EVIDENCE_REFERENCE_NON_AUTHORITATIVE,
  CONFIDENCE_BELOW_POLICY
}
