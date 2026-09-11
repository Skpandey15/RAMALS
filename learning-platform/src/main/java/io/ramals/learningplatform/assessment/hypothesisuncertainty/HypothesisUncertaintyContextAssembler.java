package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import io.ramals.learningplatform.curriculum.AssessmentItemType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns already-authoritative Java facts into a {@link HypothesisUncertaintyContext} -- the only
 * place repository state is read on the way to {@code HYPOTHESIS_UNCERTAINTY_V1} (M2-ADR-034 §10 of
 * the implementation brief: "the pure calculator should not reach into repositories
 * opportunistically"). {@link HypothesisUncertaintyCalculatorV1} never depends on this class or on
 * {@link HypothesisUncertaintyRepository}.
 *
 * <p><b>Nothing calls this class at runtime yet.</b> It exists so Step 1 is a genuine, testable
 * foundation rather than a calculator with no realistic caller -- consistent with M2-ADR-034
 * Amendment 1 §M's "a pure calculator + a context assembler" -- but it is wired into no selector, no
 * controller, and no scheduled job (Amendment 1 §M, prompt §28: inert).
 *
 * <p><b>Evidence boundary (Amendment 1 §F).</b> Only {@link
 * HypothesisUncertaintyRepository#findPerInteractionEvidence}, scoped to exactly the supplied {@code
 * interactionId} (one attempt), is read. No H6, no H7, no cross-attempt H5 history, no misconception
 * evidence, and no M2-ADR-033 graph read appears anywhere in this class.
 *
 * <p><b>De-duplication by observation id (Amendment 1 §R) happens here, not in the calculator.</b>
 * A governed observation can legitimately reach this method through more than one projection (e.g.
 * an H5-shaped read and a probe-provenance read both naming the same {@code
 * core.diagnostic_probe_provenance} row). Such a benign repeat -- the same id, attributed to the
 * same hypothesis, classified to the same outcome -- is folded into a single {@link
 * HypothesisEvidenceInput} before any {@link HypothesisUncertaintyContext} is built, so it
 * influences a hypothesis exactly once (§J vector 10). A repeat that instead <em>disagrees</em> --
 * a different hypothesis or a different outcome for the same observation id -- is corrupt input:
 * this method fails closed rather than silently choosing one of the conflicting records. {@link
 * HypothesisUncertaintyCalculatorV1} performs no de-duplication of its own; a repeated id that still
 * reaches it is refused as {@code DUPLICATE_EVIDENCE_OBSERVATION}, the fail-closed backstop for a
 * mis-assembled context.
 */
@Service
public class HypothesisUncertaintyContextAssembler {

  private final HypothesisUncertaintyRepository repository;

  public HypothesisUncertaintyContextAssembler(HypothesisUncertaintyRepository repository) {
    this.repository = repository;
  }

  /**
   * Assembles the context for one diagnostic interaction and its already-authorized candidate
   * hypothesis set (Amendment 1 §E -- this method creates no candidate of its own; {@code
   * candidates} must already be resolved by the existing deterministic hypothesis machinery).
   *
   * <p>The context's single {@code domainCode} is resolved from the shared trigger objective every
   * candidate in one V5 resolution is raised from; each candidate's own {@code domainCode} is
   * resolved independently from its target objective, so a hand-authored relationship that
   * (incorrectly) points cross-domain is caught by {@link HypothesisUncertaintyCalculatorV1}'s own
   * validation rather than silently assembled away.
   *
   * @throws HypothesisUncertaintyAssemblyException if an objective's domain cannot be resolved, or
   *     if the same governed observation id is returned with disagreeing hypothesis/outcome by more
   *     than one read (a data-integrity failure, never silently resolved by picking one)
   * @throws IllegalArgumentException if {@code candidates} is empty -- there is no trigger objective
   *     to resolve a domain from; call sites should not invoke the assembler for an empty set
   *     (the calculator's own {@code NOT_APPLICABLE} handling is for a context, not for skipping
   *     assembly)
   */
  public HypothesisUncertaintyContext assemble(UUID interactionId, List<DiagnosticHypothesis> candidates) {
    if (candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "at least one candidate hypothesis is required to assemble a context");
    }

    Set<UUID> objectiveIds = new LinkedHashSet<>();
    for (DiagnosticHypothesis hypothesis : candidates) {
      objectiveIds.add(hypothesis.triggerObjectiveId());
      objectiveIds.add(hypothesis.targetObjectiveId());
    }
    Map<UUID, String> domainByObjectiveId = repository.findObjectiveDomainCodes(objectiveIds);

    UUID triggerObjectiveId = candidates.get(0).triggerObjectiveId();
    String domainCode = domainOf(domainByObjectiveId, triggerObjectiveId);

    List<CandidateHypothesis> candidateHypotheses = new ArrayList<>(candidates.size());
    // Keyed by observation id so the same governed observation, however many projections return it,
    // is folded into exactly one HypothesisEvidenceInput before the context is built (§R).
    Map<UUID, HypothesisEvidenceInput> evidenceByObservationId = new LinkedHashMap<>();
    for (DiagnosticHypothesis hypothesis : candidates) {
      String candidateDomainCode = domainOf(domainByObjectiveId, hypothesis.targetObjectiveId());
      candidateHypotheses.add(new CandidateHypothesis(hypothesis, candidateDomainCode));

      for (HypothesisUncertaintyRepository.RawObservation raw : repository.findPerInteractionEvidence(
          interactionId, hypothesis.triggerObjectiveId(), hypothesis.targetObjectiveId(),
          hypothesis.relationshipType())) {
        HypothesisEvidenceOutcome outcome = HypothesisEvidenceOutcome.classify(
            AssessmentItemType.of(raw.itemType()), raw.isCorrect());
        HypothesisEvidenceInput input = new HypothesisEvidenceInput(
            raw.observationId(), hypothesis, outcome, interactionId, candidateDomainCode);

        HypothesisEvidenceInput existing = evidenceByObservationId.putIfAbsent(raw.observationId(), input);
        if (existing != null && !existing.equals(input)) {
          throw HypothesisUncertaintyAssemblyException.conflictingObservation(raw.observationId());
        }
        // existing != null && existing.equals(input): the same observation reached this method
        // through another projection -- already recorded once, nothing more to do (§J vector 10).
      }
    }

    return new HypothesisUncertaintyContext(
        interactionId, domainCode, candidateHypotheses, List.copyOf(evidenceByObservationId.values()));
  }

  private static String domainOf(Map<UUID, String> domainByObjectiveId, UUID objectiveId) {
    String domainCode = domainByObjectiveId.get(objectiveId);
    if (domainCode == null) {
      throw HypothesisUncertaintyAssemblyException.unresolvableDomain(objectiveId);
    }
    return domainCode;
  }
}
