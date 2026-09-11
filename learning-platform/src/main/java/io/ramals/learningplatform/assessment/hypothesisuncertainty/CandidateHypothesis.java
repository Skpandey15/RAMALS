package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;

/**
 * One authorized candidate in a {@link HypothesisUncertaintyContext}: an existing, already-resolved
 * {@link DiagnosticHypothesis} (M2-ADR-034 Amendment 1 §E -- this type creates no eligibility of its
 * own) together with the curriculum domain that hypothesis's objectives resolve to, so the
 * calculator can re-check the one-domain invariant (§Q) without querying anything itself.
 *
 * @param domainCode resolved from the hypothesis's own objectives' authoritative curriculum context
 *     (never inferred, never defaulted) -- must equal the owning {@link HypothesisUncertaintyContext}'s
 *     {@code domainCode} or the context is refused ({@code CROSS_DOMAIN_CANDIDATE_SET})
 */
public record CandidateHypothesis(DiagnosticHypothesis hypothesis, String domainCode) {

  public CandidateHypothesis {
    if (hypothesis == null) {
      throw new IllegalArgumentException("candidate hypothesis is required");
    }
    if (domainCode == null || domainCode.isBlank()) {
      throw new IllegalArgumentException("candidate domain code is required");
    }
  }
}
