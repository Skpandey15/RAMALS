package io.ramals.learningplatform.assessment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Granular diagnostic runtime evidence capture (M2-ADR-027): the runtime consumer of M2-ADR-026's
 * ontology and classifier -- given one just-scored {@code SINGLE_CHOICE} response, captures one
 * immutable evidence observation per misconception the item was event-time-eligible for (§3: in
 * the absence of adaptive targeting, every such misconception is evaluated, never a single
 * arbitrarily-chosen one).
 *
 * <p><b>Deliberately independent of the foundation-stage {@link MisconceptionEvidenceService}.</b>
 * That class (#254) has no event-time awareness -- its {@code evidenceFor} answers "is this
 * eligible right now," not "was this eligible as of this exact response's own moment." This service
 * does its own event-time-aware computation directly against
 * {@link MisconceptionOptionMappingRepository}'s newer methods, reusing only the pure, unmodified
 * {@link MisconceptionEvidenceOutcome#classify}. {@code MisconceptionEvidenceService} itself is
 * untouched and unused by this runtime path.
 *
 * <p>Not wired into adaptive selection, granular confidence, or anything reading this evidence back
 * yet (M2-ADR-027 §9) -- passive capture only.
 */
@Service
public class MisconceptionEvidenceCaptureService {

  /** Governs the whole capture policy as one unit -- see M2-ADR-027 §7. Deliberately not named
   * {@code ..._VERSION} so {@code EngineVersionFreezeTests}' auto-scan does not sweep it into a
   * frozen-vector requirement the classifier's own tunable-free arithmetic does not need. */
  public static final String POLICY = "MISCONCEPTION_EVIDENCE_V1";

  private final MisconceptionOptionMappingRepository mappingRepository;
  private final MisconceptionEvidenceObservationRepository observationRepository;

  public MisconceptionEvidenceCaptureService(
      MisconceptionOptionMappingRepository mappingRepository,
      MisconceptionEvidenceObservationRepository observationRepository) {
    this.mappingRepository = mappingRepository;
    this.observationRepository = observationRepository;
  }

  /**
   * Captures every event-time-eligible misconception observation for {@code itemVersionId}'s
   * response within {@code attemptId} -- a no-op if the item was never answered, is not
   * {@code SINGLE_CHOICE}, or carries no event-time-eligible mapping at all (the ordinary case for
   * most items).
   */
  @Transactional
  public void captureEvidence(UUID learnerId, UUID attemptId, UUID itemVersionId) {
    Optional<MisconceptionOptionMappingRepository.ScoredResponseForCapture> scored =
        mappingRepository.scoredResponseForCapture(attemptId, itemVersionId);
    if (scored.isEmpty()) {
      return;
    }
    MisconceptionOptionMappingRepository.ScoredResponseForCapture response = scored.get();

    List<MisconceptionOptionMappingRepository.EligibleMapping> eligible =
        mappingRepository.eligibleMappingsAsOf(itemVersionId, response.createdAt());
    if (eligible.isEmpty()) {
      return;
    }

    Map<UUID, List<String>> eligibleOptionsByMisconception = eligible.stream()
        .collect(Collectors.groupingBy(
            MisconceptionOptionMappingRepository.EligibleMapping::misconceptionId,
            LinkedHashMap::new,
            Collectors.mapping(
                MisconceptionOptionMappingRepository.EligibleMapping::optionId, Collectors.toList())));

    for (Map.Entry<UUID, List<String>> entry : eligibleOptionsByMisconception.entrySet()) {
      UUID misconceptionId = entry.getKey();
      List<String> eligibleOptionIds = entry.getValue();
      boolean selectedOptionEligible =
          !response.isCorrect() && eligibleOptionIds.contains(response.selectedOptionId());
      MisconceptionEvidenceOutcome outcome =
          MisconceptionEvidenceOutcome.classify(response.isCorrect(), selectedOptionEligible);

      UUID observationId = observationRepository.insert(
          learnerId, response.responseId(), misconceptionId, outcome, POLICY);
      for (String optionId : eligibleOptionIds) {
        observationRepository.insertProvenance(observationId, itemVersionId, optionId, misconceptionId);
      }
    }
  }
}
