package io.ramals.learningplatform.assessment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): turns one already-scored
 * {@code SINGLE_CHOICE} response into misconception evidence, purely by reading -- writes nothing,
 * wired into no runtime path. Read-only, by construction, the same discipline
 * {@code ProbeRelationshipService} already holds H4b's own foundation to.
 *
 * <p><b>Called by nothing at runtime yet.</b> This is deliberately not wired into
 * {@code DiagnosticService} or {@code DiagnosticSubmissionService} -- see M2-ADR-026 §8. It exists
 * to be reviewed, and tested end to end against real content, on its own, the same way H4b's own
 * foundation (#251) preceded its runtime consumer by a separate, later-reviewed PR.
 */
@Service
public class MisconceptionEvidenceService {

  private final MisconceptionOptionMappingRepository mappingRepository;

  public MisconceptionEvidenceService(MisconceptionOptionMappingRepository mappingRepository) {
    this.mappingRepository = mappingRepository;
  }

  /**
   * Classifies {@code itemVersionId}'s response within {@code attemptId} as evidence for
   * {@code misconceptionId} -- empty if the item was never answered in that attempt, is not
   * {@code SINGLE_CHOICE}, or is not misconception-evidence-eligible for {@code misconceptionId}
   * (carries no {@code PUBLISHED} {@code core.assessment_item_option_misconception} row naming it).
   * Ineligibility is this method returning empty, never a value of
   * {@link MisconceptionEvidenceOutcome} -- see that enum's own javadoc.
   */
  @Transactional(readOnly = true)
  public Optional<MisconceptionEvidenceOutcome> evidenceFor(
      UUID misconceptionId, UUID attemptId, UUID itemVersionId) {
    if (!mappingRepository.isEvidenceEligible(itemVersionId, misconceptionId)) {
      return Optional.empty();
    }
    return mappingRepository.scoredResponse(attemptId, itemVersionId)
        .map(response -> MisconceptionEvidenceOutcome.classify(
            response.isCorrect(),
            !response.isCorrect() && mappingRepository.isOptionPublishedForMisconception(
                itemVersionId, response.selectedOptionId(), misconceptionId)));
  }
}
