package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M2-ADR-030 (H7): a deterministic, read-only projection over already-governed G2/G3 facts. G3
 * answers "what does accumulated governed evidence say about misconception M?" (a cumulative,
 * ever-growing aggregate). H7 answers "what governed evidence concerning M has appeared after a
 * fixed baseline evidentiary boundary?" (a bounded, post-baseline-only view). H7 is not verification
 * of the misconception, confirmation, resolution, recurrence detection, regression detection,
 * mastery, or root-cause reasoning -- it exposes no mastery field at all and never reads H5's own
 * hypothesis-tuple stream.
 *
 * <p>Two identically-shaped uses: a domain summary ({@code domainCode} set, one finding per
 * misconception with {@link LongitudinalDataStatus#HAS_BASELINE} in that domain -- a misconception
 * with {@link LongitudinalDataStatus#NO_BASELINE} is absent entirely, mirroring H6's own "zero
 * evidence is absence, not a row" convention) and a single-misconception detail ({@code domainCode}
 * null, exactly one finding, present even when {@link LongitudinalDataStatus#NO_BASELINE} -- an
 * existing misconception the learner has no eligible baseline for yet is a valid 200 response, never
 * a 404 and never a manufactured state).
 */
public record LongitudinalEvidenceReport(
    UUID learnerId,
    String domainCode,
    Instant generatedAt,
    List<LongitudinalEvidenceFinding> findings) {

  /**
   * One misconception's longitudinal projection.
   *
   * @param dataStatus {@link LongitudinalDataStatus#NO_BASELINE} iff no eligible persisted G3
   *     baseline exists for {@code (learnerId, misconceptionId)} -- distinct from, and never
   *     conflated with, {@link LongitudinalEvidenceState#NO_LATER_EVIDENCE}, which is itself a real
   *     classification meaningful only once a baseline already exists
   * @param baseline the fixed evidentiary boundary -- {@code null} iff {@code dataStatus ==
   *     NO_BASELINE}
   * @param state the {@code LONGITUDINAL_EVIDENCE_V1} classification of post-baseline evidence --
   *     {@code null} iff {@code dataStatus == NO_BASELINE}
   * @param laterEvidence raw post-baseline {@code SUPPORTING}/{@code CONTRADICTORY}/{@code
   *     INCONCLUSIVE} counts -- all zero iff {@code dataStatus == NO_BASELINE}
   * @param laterEvidenceObservationIds every post-baseline evidence-observation id, ordered {@code
   *     created_at ASC, id ASC} -- a deterministic presentation ordering only, never a causal or
   *     generation-order guarantee when both tie (see {@link LongitudinalEvidenceService}). Empty
   *     iff {@code dataStatus == NO_BASELINE}
   * @param latestConfidence the latest persisted G3 snapshot for this pair, exposed as separate
   *     context, worded "latest persisted overall evidence strength" -- never "current confidence" --
   *     and never conflated with {@code state}. {@code null} iff no G3 snapshot at all exists yet for
   *     this pair (independent of {@code dataStatus}: a learner can have G2 evidence and a G3
   *     snapshot without yet clearing the baseline threshold only in the impossible case counts are
   *     both zero, so in practice {@code latestConfidence} and {@code baseline} become non-null
   *     together, but they are computed, and must be read, independently)
   * @param confidenceCoverage {@code CURRENT} iff every evidence row for the pair is cited by {@code
   *     latestConfidence}'s own provenance, {@code STALE_RELATIVE_TO_LATER_EVIDENCE} otherwise --
   *     {@code null} iff {@code latestConfidence == null}. Never {@code CURRENT} without a persisted
   *     snapshot to prove it against.
   * @param policyVersion {@code LongitudinalEvidencePolicyV1.POLICY_VERSION} -- {@code null} iff
   *     {@code dataStatus == NO_BASELINE} (no classification was computed)
   */
  public record LongitudinalEvidenceFinding(
      UUID misconceptionId,
      String name,
      String description,
      MisconceptionTargetType targetType,
      UUID targetId,
      DiagnosticReport.ObjectiveContext objectiveContext,
      DiagnosticReport.ConceptContext conceptContext,
      DiagnosticReport.SubConceptContext subConceptContext,
      LongitudinalDataStatus dataStatus,
      Baseline baseline,
      LongitudinalEvidenceState state,
      DiagnosticReport.EvidenceSummary laterEvidence,
      List<UUID> laterEvidenceObservationIds,
      LatestConfidence latestConfidence,
      ConfidenceCoverage confidenceCoverage,
      String policyVersion) {
  }

  /** The fixed evidentiary boundary: the earliest persisted G3 snapshot for this pair with {@code
   * supporting_count + contradictory_count > 0}. {@code evidenceStrength} is worded "baseline
   * evidence strength" -- never "as first established", which would imply the misconception itself
   * was established. */
  public record Baseline(
      UUID confidenceSnapshotId, DiagnosticConfidenceBand evidenceStrength, Instant computedAt) {
  }

  /** The latest persisted G3 snapshot for this pair, read back verbatim -- never recomputed by H7.
   * {@code evidenceStrength} is worded "latest persisted overall evidence strength". */
  public record LatestConfidence(
      UUID confidenceSnapshotId, UUID attemptId, DiagnosticConfidenceBand evidenceStrength,
      Instant computedAt) {
  }
}
