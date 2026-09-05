package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2-ADR-028: the runtime consumer of G2's evidence model (M2-ADR-027) -- given one just-completed
 * submission, determines every misconception it produced evidence for and recomputes each exactly
 * once, over the complete accumulated {@code MISCONCEPTION_EVIDENCE_V1} evidence for {@code
 * (learnerId, misconceptionId)} (which may include evidence from earlier submissions and earlier
 * assessment versions -- M2-ADR-028 §2). Called from inside {@code DiagnosticSubmissionService.score}'s
 * existing transaction, after its per-response loop finishes; adds no transaction boundary of its
 * own, and a failure here rolls back the entire submission, exactly like every other side effect
 * {@code score} already performs.
 *
 * <p><b>Reuses {@link DiagnosticConfidenceCalculatorV1} directly, unmodified.</b> No new calculator,
 * no adapter -- {@link DiagnosticConfidenceInputs} already accepts exactly the three counts this
 * service produces. This is deliberately the second, independent consumer of that calculator; H5's
 * own {@code DiagnosticConfidenceService}/{@code DiagnosticConfidenceRepository} are untouched and
 * unused here.
 *
 * <p><b>Exactly one snapshot per affected misconception per submission.</b> A submission with several
 * responses that all produce evidence for the same misconception still yields exactly one confidence
 * row for it, computed after every response in this submission has already been scored and captured
 * -- never one row per individual evidence observation, which would manufacture artificial diagnostic
 * history within a single submission (M2-ADR-028 §4).
 */
@Service
public class MisconceptionConfidenceService {

  private final MisconceptionConfidenceRepository repository;
  private final DiagnosticConfidenceCalculatorV1 calculator;

  public MisconceptionConfidenceService(
      MisconceptionConfidenceRepository repository, DiagnosticConfidenceCalculatorV1 calculator) {
    this.repository = repository;
    this.calculator = calculator;
  }

  /**
   * Recomputes confidence for every misconception {@code attemptId}'s own responses produced
   * evidence for -- a no-op if this attempt produced no misconception evidence at all (the ordinary
   * case for most submissions).
   */
  @Transactional
  public void recomputeForAttempt(UUID attemptId, UUID learnerId) {
    Set<UUID> affectedMisconceptionIds = repository.distinctMisconceptionIdsForAttempt(attemptId);
    for (UUID misconceptionId : affectedMisconceptionIds) {
      recomputeOne(attemptId, learnerId, misconceptionId);
    }
  }

  private void recomputeOne(UUID attemptId, UUID learnerId, UUID misconceptionId) {
    List<MisconceptionConfidenceRepository.EvidenceObservationSummary> evidence =
        repository.evidenceObservationsFor(learnerId, misconceptionId);

    int supporting = 0;
    int contradictory = 0;
    int inconclusive = 0;
    for (MisconceptionConfidenceRepository.EvidenceObservationSummary summary : evidence) {
      switch (summary.outcome()) {
        case SUPPORTING -> supporting++;
        case CONTRADICTORY -> contradictory++;
        case INCONCLUSIVE -> inconclusive++;
      }
    }

    DiagnosticConfidenceResult result =
        calculator.compute(new DiagnosticConfidenceInputs(supporting, contradictory, inconclusive));

    UUID confidenceObservationId = repository.insert(attemptId, learnerId, misconceptionId, result);
    for (MisconceptionConfidenceRepository.EvidenceObservationSummary summary : evidence) {
      repository.insertProvenance(confidenceObservationId, summary.id());
    }
  }
}
