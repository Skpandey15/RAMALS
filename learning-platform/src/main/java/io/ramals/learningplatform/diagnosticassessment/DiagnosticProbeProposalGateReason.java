package io.ramals.learningplatform.diagnosticassessment;

/**
 * Stable, observable reason codes for the advisory diagnostic-probe proposal gate (M2-ADR-032 4).
 * Never model-authored prose; every value maps to one of the ADR's fifteen mandatory checks or its
 * semantic-safety rules, so a rejection is explainable from persisted, versioned deterministic
 * inputs alone.
 */
public enum DiagnosticProbeProposalGateReason {

  /** All checks passed. Acceptance means the recommendation is well-formed, evidence-grounded and
   * in scope -- never that a probe will run (M2-ADR-032 13; eligibility stays with
   * DIAGNOSTIC_SELECTION_V1-V5, untouched). */
  ACCEPTED,

  /** The payload could not be read as the v1 contract at all (parser reason code carries detail). */
  PROPOSAL_MALFORMED,

  /** 4.1 -- schema/contract version this deployed gate does not accept. */
  PROPOSAL_CONTRACT_VERSION_UNSUPPORTED,

  /** 4.1 -- proposalType is not the single discriminator this contract carries. */
  PROPOSAL_TYPE_UNSUPPORTED,

  /** 3/22 -- a forbidden field (bare confidence/probability/rank/diagnosis/learner id) was present. */
  PROPOSAL_CONTAINS_FORBIDDEN_FIELD,

  /** 4.3 -- the proposal's echoed interactionId does not match the interaction that produced it. */
  INTERACTION_BINDING_MISMATCH,

  /** 4.4 -- the proposal's domain does not match the interaction's authorized domain. */
  DOMAIN_BINDING_MISMATCH,

  /** 4.5 -- the target misconception does not exist in authoritative state. */
  TARGET_MISCONCEPTION_NOT_FOUND,

  /** 4.7 -- the target misconception exists but is not PUBLISHED (M2-ADR-026 4). */
  TARGET_MISCONCEPTION_NOT_PUBLISHED,

  /** 4.6 / 12 -- the target misconception is not in the set exposed to this interaction (M_allowed). */
  TARGET_MISCONCEPTION_OUT_OF_SCOPE,

  /** 4.7 -- the target ontology node does not exist. */
  TARGET_NODE_NOT_FOUND,

  /** 4.7 / 10 -- the cited targetNode (kind or id) does not match the misconception's real
   * exclusive-arc target. */
  TARGET_NODE_ARC_MISMATCH,

  /** 4.8 / 11 -- a cited evidence reference was not among the governed set supplied to this
   * interaction (E_proposed is not a subset of E_allowed). */
  EVIDENCE_REFERENCE_NOT_IN_CONTEXT,

  /** 4.12 -- an identifier in the proposal is outside every authorized/allowed set. */
  UNAUTHORIZED_IDENTIFIER_PRESENT,

  /** 4.13 -- a named candidate probe reference is not among the references authorized for this
   * interaction. */
  CANDIDATE_PROBE_REFERENCE_NOT_AUTHORIZED,

  /** 4.13 -- the probe intent's implied capability is outside the delegated capability allowlist
   * (M2-ADR-031). */
  CAPABILITY_OUT_OF_SCOPE,

  /** 8/9 / M2-ADR-030 G -- the rationale used probability/percentage/comparative or resolution
   * terminology forbidden on an evidence-acquisition recommendation. */
  RATIONALE_FORBIDDEN_TERMINOLOGY,

  /** 14 -- a validation could not be completed (unavailable authoritative data, ambiguous binding).
   * Fail-closed: treated identically to an outright failure (M2-ADR-032 14). */
  VALIDATION_UNAVAILABLE
}
