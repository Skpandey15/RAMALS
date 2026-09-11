package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import java.util.UUID;

/**
 * One governed evidence observation offered as input to {@code HYPOTHESIS_UNCERTAINTY_V1}
 * (M2-ADR-034 Amendment 1 §F/§R). Already classified by the existing, unmodified {@link
 * HypothesisEvidenceOutcome} -- this type carries no raw answer, no item content, and no AI
 * rationale (Amendment 1 §K.1); it is exactly the fact H5's own {@code DiagnosticConfidenceService}
 * already turns one scored probe response into.
 *
 * @param observationId the authoritative identity of this observation -- the persisted {@code
 *     core.diagnostic_probe_provenance} row id, the same identity H5 counts "distinct evidence
 *     observations" by (Amendment 1 §R). {@link HypothesisUncertaintyContextAssembler} is where a
 *     benign repeat (the same observation reaching the pipeline through more than one projection,
 *     §J vector 10) is de-duplicated to one input <em>before</em> a {@link
 *     HypothesisUncertaintyContext} is ever built. Once inside a context, every {@code
 *     observationId} must be unique: if it repeats here -- agreeing or not -- {@link
 *     HypothesisUncertaintyCalculatorV1} refuses the whole context ({@code
 *     DUPLICATE_EVIDENCE_OBSERVATION}) rather than de-duplicating or choosing one record itself
 *     (§R: "the calculator does no de-duplication of its own").
 * @param hypothesis which candidate hypothesis tuple this observation is evidence for -- must be one
 *     of the owning context's candidates or the context is refused ({@code
 *     EVIDENCE_FOR_UNKNOWN_HYPOTHESIS})
 * @param interactionId the diagnostic interaction (attempt) this observation was produced in -- must
 *     equal the owning context's {@code interactionId} (§F: per-interaction only) or the context is
 *     refused ({@code EVIDENCE_INTERACTION_MISMATCH})
 * @param domainCode the curriculum domain this observation belongs to -- must equal {@code
 *     hypothesis}'s own resolved domain (see {@link CandidateHypothesis#domainCode()}) or the
 *     context is refused ({@code CROSS_DOMAIN_EVIDENCE})
 */
public record HypothesisEvidenceInput(
    UUID observationId,
    DiagnosticHypothesis hypothesis,
    HypothesisEvidenceOutcome outcome,
    UUID interactionId,
    String domainCode) {

  public HypothesisEvidenceInput {
    if (observationId == null) {
      throw new IllegalArgumentException("evidence observation id is required");
    }
    if (hypothesis == null) {
      throw new IllegalArgumentException("evidence hypothesis reference is required");
    }
    if (outcome == null) {
      throw new IllegalArgumentException("evidence outcome is required");
    }
    if (interactionId == null) {
      throw new IllegalArgumentException("evidence interaction id is required");
    }
    if (domainCode == null || domainCode.isBlank()) {
      throw new IllegalArgumentException("evidence domain code is required");
    }
  }
}
