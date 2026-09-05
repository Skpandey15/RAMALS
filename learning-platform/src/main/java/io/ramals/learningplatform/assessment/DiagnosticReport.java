package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.mastery.MasteryMapEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M2-ADR-029 (H6): a deterministic, read-only composition of governed diagnostic facts RAMALS
 * already owns -- never a new diagnosis, never a recomputed confidence, never H5 hypothesis data
 * (out of scope for V1). Two distinct identities, never conflated:
 *
 * <ul>
 *   <li>{@link ReportMode#CURRENT_DOMAIN}: the complete current diagnostic view for {@code
 *       (learnerId, domainCode)} -- every misconception the learner has evidence for in that domain,
 *       each at its own latest governed state.
 *   <li>{@link ReportMode#ATTEMPT}: the diagnostic findings {@code attemptId} itself produced --
 *       never described as, and never substituted with, "learner state as of this attempt." A
 *       misconception this attempt did not touch is absent, even if the learner has older evidence
 *       for it from a different attempt.
 * </ul>
 *
 * A true historical/as-of-attempt learner-state report -- the latest snapshot for every
 * misconception as it stood at some past attempt's own completion -- is explicitly deferred, not
 * implemented here.
 */
public record DiagnosticReport(
    ReportMode mode,
    UUID learnerId,
    String domainCode,
    UUID attemptId,
    Instant generatedAt,
    DiagnosticDataStatus diagnosticDataStatus,
    List<MisconceptionFinding> misconceptionFindings,
    List<MasteryMapEntry> mastery) {

  /** Which of the two report identities this is. */
  public enum ReportMode {
    CURRENT_DOMAIN,
    ATTEMPT
  }

  /**
   * Whether this learner has any {@code MISCONCEPTION_EVIDENCE_V1} evidence at all in this report's
   * own scope (the requested domain, or this specific attempt) -- distinct from {@link
   * ConfidenceState}, which is a per-finding fact about whether G3 has assessed one particular
   * misconception yet.
   */
  public enum DiagnosticDataStatus {
    NO_EVIDENCE,
    HAS_EVIDENCE
  }

  /**
   * Whether a persisted G3 confidence snapshot exists for one finding -- never computed by H6 itself
   * (M2-ADR-029 §E: H6 never invokes {@code DiagnosticConfidenceCalculatorV1}).
   */
  public enum ConfidenceState {
    /** G2 evidence exists; no {@code core.misconception_confidence_observation} row exists yet.
     * {@code confidence} is {@code null}. Distinct from {@code ASSESSED} with band {@code
     * INSUFFICIENT_EVIDENCE}, which is a real, persisted governed result. */
    NOT_ASSESSED,
    /** A persisted G3 snapshot exists; {@code confidence} carries its band verbatim, including
     * {@code INSUFFICIENT_EVIDENCE}. */
    ASSESSED
  }

  /**
   * One misconception's finding. Absent from a report entirely if the learner has zero {@code
   * MISCONCEPTION_EVIDENCE_V1} evidence for it -- H6 never enumerates the full authored-misconception
   * catalogue (that belongs to content/coverage APIs).
   *
   * @param targetType which single level this misconception targets -- {@code targetId} is the
   *     structural truth; {@code objectiveContext}/{@code conceptContext}/{@code subConceptContext}
   *     below are display-only ancestry, never a claim that the misconception is stored beneath them
   * @param confidenceSnapshotId the exact {@code core.misconception_confidence_observation} row id
   *     this finding's confidence came from -- {@code null} when {@code confidenceState} is {@code
   *     NOT_ASSESSED}. Exact provenance; never serialized to a learner-facing response (M2-ADR-029
   *     §I), only to an admin one
   * @param confidenceAttemptId the attempt that computed {@code confidenceSnapshotId} -- for {@link
   *     ReportMode#ATTEMPT} this always equals the report's own {@code attemptId}; for {@link
   *     ReportMode#CURRENT_DOMAIN} it is whichever attempt most recently recomputed this
   *     misconception, which may be older than the report itself. Exact provenance; admin-only
   * @param evidenceObservationIds the confidence snapshot's own complete provenance set (M2-ADR-028
   *     §7) -- empty when {@code confidenceState} is {@code NOT_ASSESSED} (nothing has been cited
   *     yet). Exact provenance; admin-only
   */
  public record MisconceptionFinding(
      UUID misconceptionId,
      String name,
      String description,
      MisconceptionTargetType targetType,
      UUID targetId,
      ObjectiveContext objectiveContext,
      ConceptContext conceptContext,
      SubConceptContext subConceptContext,
      EvidenceSummary evidenceSummary,
      ConfidenceState confidenceState,
      ConfidenceView confidence,
      UUID confidenceSnapshotId,
      UUID confidenceAttemptId,
      List<UUID> evidenceObservationIds) {
  }

  /** Display ancestry for the objective every misconception ultimately resolves to (directly, or
   * through a concept/sub-concept). Always present. */
  public record ObjectiveContext(UUID objectiveId, String objectiveCode, String description) {
  }

  /** Display ancestry for a CONCEPT -- present iff {@code targetType} is {@code CONCEPT} or {@code
   * SUB_CONCEPT}. */
  public record ConceptContext(UUID conceptId, String name) {
  }

  /** Display ancestry for a SUB_CONCEPT -- present iff {@code targetType} is {@code SUB_CONCEPT}. */
  public record SubConceptContext(UUID subConceptId, String name) {
  }

  /** Raw {@code MISCONCEPTION_EVIDENCE_V1} counts -- "distinct evidence observations," never
   * described as independent evidence (no statistical-independence claim is ever made). For {@code
   * ASSESSED} findings these are the persisted snapshot's own counts; for {@code NOT_ASSESSED} they
   * are a live count of the raw evidence rows (there is no snapshot to read them from). */
  public record EvidenceSummary(int supportingCount, int contradictoryCount, int inconclusiveCount) {
  }

  /** A persisted G3 confidence result, read back verbatim -- never recomputed by H6. {@code band} is
   * evidence strength under {@code policyVersion}, never a probability, never a diagnosis, never
   * mastery or progression authority. */
  public record ConfidenceView(DiagnosticConfidenceBand band, String policyVersion, Instant computedAt) {
  }
}
