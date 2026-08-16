package io.ramals.learningplatform.ai.contract;

/**
 * What a proposal is permitted to influence.
 *
 * <p>None of these authorise an AI component to create scored evidence in MVP-1. {@code
 * VERIFIED_CONTENT} promotes <em>content</em> after approved validation or review; it never promotes
 * an AI <em>evaluation</em> into a score (M1-ADR-010).
 */
public enum TrustLevel {
  NON_AUTHORITATIVE,
  UNVERIFIED,
  VERIFIED_CONTENT,
  FORMATIVE_ONLY,
  REJECTED,
  APPROVAL_REQUIRED
}
