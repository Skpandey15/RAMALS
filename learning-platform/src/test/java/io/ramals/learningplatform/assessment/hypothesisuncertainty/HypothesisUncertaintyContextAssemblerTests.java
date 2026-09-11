package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import io.ramals.learningplatform.assessment.ProbeRelationshipType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link HypothesisUncertaintyContextAssembler} over a mocked {@link HypothesisUncertaintyRepository}:
 * domain resolution, {@link HypothesisEvidenceOutcome} classification, and the per-interaction
 * evidence boundary (M2-ADR-034 Amendment 1 §F). The repository's own SQL is exercised by the
 * existing PostgreSQL-backed suites this package's join shapes mirror (the same tables {@code
 * DiagnosticConfidenceRepository} and {@code ProbeProvenanceRepository} already read).
 */
@ExtendWith(MockitoExtension.class)
class HypothesisUncertaintyContextAssemblerTests {

  private static final UUID INTERACTION_ID = UUID.fromString("01900000-0000-7000-8000-00000000a000");
  private static final UUID TRIGGER_ITEM = UUID.fromString("01900000-0000-7000-8000-000000001000");
  private static final UUID TRIGGER_OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-000000002000");
  private static final UUID TARGET_A = UUID.fromString("01900000-0000-7000-8000-000000003001");
  private static final UUID TARGET_B = UUID.fromString("01900000-0000-7000-8000-000000003002");
  private static final UUID AUTHORIZING_RELATIONSHIP =
      UUID.fromString("01900000-0000-7000-8000-000000004000");

  @Mock private HypothesisUncertaintyRepository repository;

  private HypothesisUncertaintyContextAssembler assembler() {
    return new HypothesisUncertaintyContextAssembler(repository);
  }

  private static DiagnosticHypothesis hypothesis(UUID targetObjectiveId) {
    return new DiagnosticHypothesis(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE, targetObjectiveId,
        AUTHORIZING_RELATIONSHIP);
  }

  @Test
  @DisplayName("resolves the context domain from the shared trigger objective and each candidate's own target")
  void resolvesDomainFromTriggerAndTargetObjectives() {
    DiagnosticHypothesis a = hypothesis(TARGET_A);
    when(repository.findObjectiveDomainCodes(any())).thenReturn(Map.of(
        TRIGGER_OBJECTIVE, "KAFKA", TARGET_A, "KAFKA"));
    when(repository.findPerInteractionEvidence(eq(INTERACTION_ID), eq(TRIGGER_OBJECTIVE), eq(TARGET_A), any()))
        .thenReturn(List.of());

    HypothesisUncertaintyContext context = assembler().assemble(INTERACTION_ID, List.of(a));

    assertThat(context.domainCode()).isEqualTo("KAFKA");
    assertThat(context.candidates()).hasSize(1);
    assertThat(context.candidates().get(0).domainCode()).isEqualTo("KAFKA");
    assertThat(context.interactionId()).isEqualTo(INTERACTION_ID);
  }

  @Test
  @DisplayName("classifies each raw observation via HypothesisEvidenceOutcome, unmodified")
  void classifiesRawObservationsViaHypothesisEvidenceOutcome() {
    DiagnosticHypothesis a = hypothesis(TARGET_A);
    UUID incorrectResponseObservation = UUID.randomUUID();
    UUID correctResponseObservation = UUID.randomUUID();
    when(repository.findObjectiveDomainCodes(any())).thenReturn(Map.of(
        TRIGGER_OBJECTIVE, "KAFKA", TARGET_A, "KAFKA"));
    when(repository.findPerInteractionEvidence(INTERACTION_ID, TRIGGER_OBJECTIVE, TARGET_A,
        ProbeRelationshipType.ROOT_CAUSE_PROBE))
        .thenReturn(List.of(
            new HypothesisUncertaintyRepository.RawObservation(
                incorrectResponseObservation, false, "SINGLE_CHOICE"), // incorrect -> SUPPORTING
            new HypothesisUncertaintyRepository.RawObservation(
                correctResponseObservation, true, "SINGLE_CHOICE"))); // correct -> CONTRADICTORY

    HypothesisUncertaintyContext context = assembler().assemble(INTERACTION_ID, List.of(a));

    assertThat(context.evidence()).hasSize(2);
    assertThat(context.evidence()).anySatisfy(input -> {
      assertThat(input.observationId()).isEqualTo(incorrectResponseObservation);
      assertThat(input.outcome()).isEqualTo(HypothesisEvidenceOutcome.SUPPORTING);
      assertThat(input.interactionId()).isEqualTo(INTERACTION_ID);
      assertThat(input.domainCode()).isEqualTo("KAFKA");
      assertThat(input.hypothesis()).isEqualTo(a);
    });
    assertThat(context.evidence()).anySatisfy(input -> {
      assertThat(input.observationId()).isEqualTo(correctResponseObservation);
      assertThat(input.outcome()).isEqualTo(HypothesisEvidenceOutcome.CONTRADICTORY);
    });
  }

  @Test
  @DisplayName("ADR vector 10: the same governed observation returned through duplicate projections "
      + "is emitted into the context exactly once, and the full pipeline yields MODERATE / 1.0000")
  void sameObservationThroughDuplicateProjectionsIsEmittedOnce() {
    DiagnosticHypothesis a = hypothesis(TARGET_A);
    UUID obs1 = UUID.randomUUID();
    UUID obs2 = UUID.randomUUID();
    when(repository.findObjectiveDomainCodes(any())).thenReturn(Map.of(
        TRIGGER_OBJECTIVE, "KAFKA", TARGET_A, "KAFKA"));
    // obs1 is returned twice -- as if two projections (e.g. an H5-shaped read and a probe-provenance
    // read) both named the same core.diagnostic_probe_provenance row -- with agreeing facts.
    when(repository.findPerInteractionEvidence(INTERACTION_ID, TRIGGER_OBJECTIVE, TARGET_A,
        ProbeRelationshipType.ROOT_CAUSE_PROBE))
        .thenReturn(List.of(
            new HypothesisUncertaintyRepository.RawObservation(obs1, false, "SINGLE_CHOICE"),
            new HypothesisUncertaintyRepository.RawObservation(obs2, false, "SINGLE_CHOICE"),
            new HypothesisUncertaintyRepository.RawObservation(obs1, false, "SINGLE_CHOICE")));

    HypothesisUncertaintyContext context = assembler().assemble(INTERACTION_ID, List.of(a));

    assertThat(context.evidence()).hasSize(2);
    assertThat(context.evidence()).extracting(HypothesisEvidenceInput::observationId)
        .containsExactlyInAnyOrder(obs1, obs2);

    // End-to-end: exactly the ADR's own vector 10 outcome (Amendment 1 §J).
    HypothesisUncertaintyResult result = new HypothesisUncertaintyCalculatorV1(
        new io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1()).calculate(context);
    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.APPLICABLE);
    assertThat(result.candidates().get(0).band())
        .isEqualTo(io.ramals.learningplatform.assessment.DiagnosticConfidenceBand.MODERATE);
    assertThat(result.candidates().get(0).normalizedValue())
        .isEqualByComparingTo(new java.math.BigDecimal("1.0000"));
  }

  @Test
  @DisplayName("the same observation id returned with disagreeing facts by different reads fails "
      + "assembly closed -- it is never silently resolved by choosing one record")
  void conflictingDuplicateObservationFailsAssemblyClosed() {
    DiagnosticHypothesis a = hypothesis(TARGET_A);
    UUID obs1 = UUID.randomUUID();
    when(repository.findObjectiveDomainCodes(any())).thenReturn(Map.of(
        TRIGGER_OBJECTIVE, "KAFKA", TARGET_A, "KAFKA"));
    // The same observation id, but one read says incorrect (-> SUPPORTING) and the other says
    // correct (-> CONTRADICTORY) -- a data-integrity failure, not a benign repeat.
    when(repository.findPerInteractionEvidence(INTERACTION_ID, TRIGGER_OBJECTIVE, TARGET_A,
        ProbeRelationshipType.ROOT_CAUSE_PROBE))
        .thenReturn(List.of(
            new HypothesisUncertaintyRepository.RawObservation(obs1, false, "SINGLE_CHOICE"),
            new HypothesisUncertaintyRepository.RawObservation(obs1, true, "SINGLE_CHOICE")));

    assertThatThrownBy(() -> assembler().assemble(INTERACTION_ID, List.of(a)))
        .isInstanceOf(HypothesisUncertaintyAssemblyException.class);
  }

  @Test
  @DisplayName("reads evidence scoped to exactly the supplied interaction id, per candidate tuple")
  void readsEvidenceScopedToTheSuppliedInteractionOnly() {
    DiagnosticHypothesis a = hypothesis(TARGET_A);
    DiagnosticHypothesis b = hypothesis(TARGET_B);
    when(repository.findObjectiveDomainCodes(any())).thenReturn(Map.of(
        TRIGGER_OBJECTIVE, "KAFKA", TARGET_A, "KAFKA", TARGET_B, "KAFKA"));
    when(repository.findPerInteractionEvidence(any(), any(), any(), any())).thenReturn(List.of());

    assembler().assemble(INTERACTION_ID, List.of(a, b));

    org.mockito.Mockito.verify(repository).findPerInteractionEvidence(
        INTERACTION_ID, TRIGGER_OBJECTIVE, TARGET_A, ProbeRelationshipType.ROOT_CAUSE_PROBE);
    org.mockito.Mockito.verify(repository).findPerInteractionEvidence(
        INTERACTION_ID, TRIGGER_OBJECTIVE, TARGET_B, ProbeRelationshipType.ROOT_CAUSE_PROBE);
  }

  @Test
  @DisplayName("an unresolvable objective domain fails assembly deterministically")
  void unresolvableDomainFailsAssembly() {
    DiagnosticHypothesis a = hypothesis(TARGET_A);
    when(repository.findObjectiveDomainCodes(any())).thenReturn(Map.of());

    assertThatThrownBy(() -> assembler().assemble(INTERACTION_ID, List.of(a)))
        .isInstanceOf(HypothesisUncertaintyAssemblyException.class);
  }

  @Test
  @DisplayName("an empty candidate list is rejected -- there is no trigger objective to resolve a domain from")
  void emptyCandidateListRejected() {
    assertThatThrownBy(() -> assembler().assemble(INTERACTION_ID, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
