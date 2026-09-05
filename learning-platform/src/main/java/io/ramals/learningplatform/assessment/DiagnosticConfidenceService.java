package io.ramals.learningplatform.assessment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-023 §2): the runtime consumer of H4b's evidence model and V5's
 * provenance model -- given one just-scored response, decides whether it is a probe response (one
 * {@code core.diagnostic_probe_provenance} names), and if so, recomputes and appends the hypothesis
 * tuple's confidence observation. Called from inside
 * {@code DiagnosticSubmissionService.score}'s existing transaction; adds no transaction boundary of
 * its own, and a failure here rolls back that entire submission, exactly like every other side
 * effect {@code score} already performs (evidence recording, mastery recompute).
 *
 * <p><b>Not every scored response reaches the calculator.</b> Most responses answer an ordinary V2
 * item, never named by any provenance row -- {@link #recordObservationIfProbeResponse} is a no-op
 * for those, cheaply, via one indexed lookup. Because
 * {@code HypothesisDrivenProbeDiagnosticSelector.MAX_HYPOTHESIS_PROBES_PER_PACKET} is 1, at most one
 * response in a single submission can ever be a probe response.
 */
@Service
public class DiagnosticConfidenceService {

  private final ProbeProvenanceRepository probeProvenanceRepository;
  private final DiagnosticConfidenceRepository confidenceRepository;
  private final DiagnosticConfidenceCalculatorV1 calculator;

  public DiagnosticConfidenceService(
      ProbeProvenanceRepository probeProvenanceRepository,
      DiagnosticConfidenceRepository confidenceRepository,
      DiagnosticConfidenceCalculatorV1 calculator) {
    this.probeProvenanceRepository = probeProvenanceRepository;
    this.confidenceRepository = confidenceRepository;
    this.calculator = calculator;
  }

  /**
   * If {@code (attemptId, itemVersionId)} is a probe response -- named by a
   * {@code core.diagnostic_probe_provenance} row -- recomputes the hypothesis tuple's confidence
   * over every distinct evidence observation gathered so far (including this one, already visible
   * in this same transaction) and appends one immutable observation. A no-op, returning
   * {@link Optional#empty()}, for any response that is not a probe response.
   */
  @Transactional
  public Optional<DiagnosticConfidenceObservation> recordObservationIfProbeResponse(
      UUID attemptId, UUID itemVersionId, UUID learnerId, UUID assessmentVersionId) {
    Optional<ProbeProvenance> provenance =
        probeProvenanceRepository.findByAttemptAndItem(attemptId, itemVersionId);
    if (provenance.isEmpty()) {
      return Optional.empty();
    }

    ProbeProvenance triggering = provenance.get();
    DiagnosticConfidenceRepository.RawEvidenceCounts counts = confidenceRepository.evidenceCounts(
        learnerId, assessmentVersionId, triggering.sourceObjectiveId(), triggering.targetObjectiveId(),
        triggering.relationshipType());
    DiagnosticConfidenceResult result = calculator.compute(new DiagnosticConfidenceInputs(
        counts.supportingCount(), counts.contradictoryCount(), counts.inconclusiveCount()));

    return Optional.of(confidenceRepository.insert(
        learnerId, triggering.sourceObjectiveId(), triggering.targetObjectiveId(),
        triggering.relationshipType(), triggering.id(), result));
  }
}
