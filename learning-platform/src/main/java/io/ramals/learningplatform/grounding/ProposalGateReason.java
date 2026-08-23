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
  CONFIDENCE_BELOW_POLICY,

  // -- M2-T09 diagnostic assessment semantics ----------------------------------------------------
  //
  // Added rather than kept in a second enum so one persisted vocabulary covers every proposal type.
  // A reason code split across two taxonomies is a query nobody writes correctly the first time.

  /** A classification named a skill the grounded context does not describe. */
  SKILL_NOT_IN_CONTEXT,

  /** The context carries no authoritative skill universe against which membership can be checked. */
  SKILL_CONTEXT_MISSING,

  /** Two classifications for the same skill. The gate does not choose between them. */
  CLASSIFICATION_CONFLICT,

  /** STRONG asserted without the evidence sufficiency the policy requires for a strong reading. */
  EVIDENCE_INSUFFICIENT_FOR_STRONG,

  /** INSUFFICIENT_EVIDENCE asserted alongside a confidence that claims a conclusion anyway. */
  INSUFFICIENT_EVIDENCE_OVERCONFIDENT,

  /** A recommended next skill the grounded context does not describe. */
  RECOMMENDATION_INVALID
}
