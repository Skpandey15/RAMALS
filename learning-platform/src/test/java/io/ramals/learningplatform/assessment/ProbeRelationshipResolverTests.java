package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.AssessmentItemType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ProbeRelationshipResolver} and {@link HypothesisEvidenceOutcome}: the pure decisions behind
 * H4b's foundation, exercised with no database. Every {@link ProbeRelationshipType} is resolved
 * identically once a {@link ProbeTargetObjective} exists -- these tests confirm that uniformity as
 * much as any single type's behaviour.
 */
class ProbeRelationshipResolverTests {

  private static final UUID TRIGGER_ITEM = UUID.randomUUID();
  private static final UUID TRIGGER_OBJECTIVE = UUID.randomUUID();
  private static final UUID TARGET_OBJECTIVE = UUID.randomUUID();
  private static final UUID AUTHORIZING_RELATIONSHIP = UUID.randomUUID();

  // -----------------------------------------------------------------------------------------
  // 1. relationship resolution (general shape) / 6. unknown relationship (no target)
  // -----------------------------------------------------------------------------------------

  @Test
  void noTargetObjectiveResolvesToNoRelationshipDefinedAndRaisesNoHypothesis() {
    ProbeResolution resolution = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        null, List.of(), Set.of());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.NO_RELATIONSHIP_DEFINED);
    assertThat(resolution.hypothesis()).isNull();
    assertThat(resolution.candidates()).isEmpty();
  }

  @Test
  void aTargetWithNoItemsAtAllStillRaisesAnAuditableHypothesis() {
    ProbeTargetObjective target = new ProbeTargetObjective(TARGET_OBJECTIVE, AUTHORIZING_RELATIONSHIP);

    ProbeResolution resolution = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        target, List.of(), Set.of());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.RELATIONSHIP_DEFINED_BUT_NO_ITEMS);
    assertThat(resolution.hypothesis()).isNotNull();
    assertThat(resolution.hypothesis().targetObjectiveId()).isEqualTo(TARGET_OBJECTIVE);
    assertThat(resolution.hypothesis().authorizingRelationshipId()).isEqualTo(AUTHORIZING_RELATIONSHIP);
    assertThat(resolution.candidates()).isEmpty();
  }

  // -----------------------------------------------------------------------------------------
  // 2-5. Every relationship type is resolved through the identical mechanism once a target
  // exists -- the type only changes what is recorded on the DiagnosticHypothesis, never how
  // candidates are decided.
  // -----------------------------------------------------------------------------------------

  @Test
  void sameObjectiveConfirmationResolvesLikeEveryOtherType() {
    assertResolvesToCandidatesAvailable(ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION);
  }

  @Test
  void prerequisiteValidationResolvesLikeEveryOtherType() {
    assertResolvesToCandidatesAvailable(ProbeRelationshipType.PREREQUISITE_VALIDATION);
  }

  @Test
  void rootCauseProbeResolvesLikeEveryOtherType() {
    assertResolvesToCandidatesAvailable(ProbeRelationshipType.ROOT_CAUSE_PROBE);
  }

  @Test
  void contradictionCheckResolvesLikeEveryOtherType() {
    assertResolvesToCandidatesAvailable(ProbeRelationshipType.CONTRADICTION_CHECK);
  }

  private void assertResolvesToCandidatesAvailable(ProbeRelationshipType type) {
    ProbeTargetObjective target = new ProbeTargetObjective(TARGET_OBJECTIVE, AUTHORIZING_RELATIONSHIP);
    ProbeCandidateItem item = candidate();

    ProbeResolution resolution = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, type, target, List.of(item), Set.of());

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.CANDIDATES_AVAILABLE);
    assertThat(resolution.hypothesis().relationshipType()).isEqualTo(type);
    assertThat(resolution.candidates()).containsExactly(item);
  }

  // -----------------------------------------------------------------------------------------
  // 7-8. Multiple candidates, deterministic ordering
  // -----------------------------------------------------------------------------------------

  @Test
  void multipleUnexposedCandidatesAreAllReturnedInTheCallersOrder() {
    ProbeTargetObjective target = new ProbeTargetObjective(TARGET_OBJECTIVE, null);
    ProbeCandidateItem first = candidate();
    ProbeCandidateItem second = candidate();
    ProbeCandidateItem third = candidate();

    ProbeResolution resolution = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION,
        target, List.of(first, second, third), Set.of());

    // The resolver does not sort or shuffle; the repository's own display_order/item_code
    // ordering is preserved verbatim, so a change to iteration order here would be a real
    // regression, not a benign refactor.
    assertThat(resolution.candidates()).containsExactly(first, second, third);
  }

  // -----------------------------------------------------------------------------------------
  // 9-10. Exposure exclusion, keyed by logical identity rather than item version
  // -----------------------------------------------------------------------------------------

  @Test
  void anExposedCandidateIsExcludedButOthersRemain() {
    ProbeTargetObjective target = new ProbeTargetObjective(TARGET_OBJECTIVE, null);
    ProbeCandidateItem exposed = candidate();
    ProbeCandidateItem unseen = candidate();

    ProbeResolution resolution = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        target, List.of(exposed, unseen), Set.of(exposed.logicalItemId()));

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.CANDIDATES_AVAILABLE);
    assertThat(resolution.candidates()).containsExactly(unseen);
  }

  @Test
  void exclusionIsByLogicalIdentityNotItemVersionId() {
    // Two different editorial versions of the same logical question -- the shape V048's lineage
    // table exists to make exposure survive a revision. Excluding by itemVersionId alone would
    // wrongly let a revised item version through even though the learner already saw an earlier
    // version of the same question.
    UUID sharedLogicalId = UUID.randomUUID();
    ProbeCandidateItem revisedVersion = new ProbeCandidateItem(UUID.randomUUID(), sharedLogicalId);
    ProbeTargetObjective target = new ProbeTargetObjective(TARGET_OBJECTIVE, null);

    ProbeResolution resolution = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION,
        target, List.of(revisedVersion), Set.of(sharedLogicalId));

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.ALL_CANDIDATES_ALREADY_EXPOSED);
    assertThat(resolution.candidates()).isEmpty();
  }

  @Test
  void everyCandidateExposedIsAllCandidatesAlreadyExposedNotNoRelationshipDefined() {
    // A relationship that is real, published, and has real content is a materially different
    // condition from one that was never defined -- this must not collapse to the same outcome as
    // NO_RELATIONSHIP_DEFINED just because nothing is servable right now.
    ProbeTargetObjective target = new ProbeTargetObjective(TARGET_OBJECTIVE, AUTHORIZING_RELATIONSHIP);
    ProbeCandidateItem onlyItem = candidate();

    ProbeResolution resolution = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        target, List.of(onlyItem), Set.of(onlyItem.logicalItemId()));

    assertThat(resolution.outcome()).isEqualTo(ProbeResolutionOutcome.ALL_CANDIDATES_ALREADY_EXPOSED);
    assertThat(resolution.hypothesis()).isNotNull();
  }

  // -----------------------------------------------------------------------------------------
  // 11-12. Evidence classification
  // -----------------------------------------------------------------------------------------

  @Test
  void anIncorrectDeterministicallyScoreableProbeIsSupportingEvidence() {
    assertThat(HypothesisEvidenceOutcome.classify(AssessmentItemType.SINGLE_CHOICE, false))
        .isEqualTo(HypothesisEvidenceOutcome.SUPPORTING);
    assertThat(HypothesisEvidenceOutcome.classify(AssessmentItemType.FILL_BLANK, false))
        .isEqualTo(HypothesisEvidenceOutcome.SUPPORTING);
  }

  @Test
  void aCorrectDeterministicallyScoreableProbeIsContradictoryEvidence() {
    assertThat(HypothesisEvidenceOutcome.classify(AssessmentItemType.SINGLE_CHOICE, true))
        .isEqualTo(HypothesisEvidenceOutcome.CONTRADICTORY);
    assertThat(HypothesisEvidenceOutcome.classify(AssessmentItemType.FILL_BLANK, true))
        .isEqualTo(HypothesisEvidenceOutcome.CONTRADICTORY);
  }

  @Test
  void aNonDeterministicallyScoreableProbeIsInconclusiveRegardlessOfCorrectness() {
    // SHORT_ANSWER/USE_CASE never reach a learner's form today (AssessmentItemType.scoreable()),
    // so this branch is unreachable in production -- reserved, not omitted. See M2-ADR-024 and
    // HypothesisEvidenceOutcome's own javadoc.
    assertThat(HypothesisEvidenceOutcome.classify(AssessmentItemType.SHORT_ANSWER, true))
        .isEqualTo(HypothesisEvidenceOutcome.INCONCLUSIVE);
    assertThat(HypothesisEvidenceOutcome.classify(AssessmentItemType.USE_CASE, false))
        .isEqualTo(HypothesisEvidenceOutcome.INCONCLUSIVE);
  }

  // -----------------------------------------------------------------------------------------
  // 13. Prerequisite relationship remains evidence, never a gate
  // -----------------------------------------------------------------------------------------

  @Test
  void prerequisiteValidationNeverExcludesItIsResolvedIdenticallyToEveryOtherType() {
    // M2-ADR-023 §1's "evidence, never a gate" binds PREREQUISITE_VALIDATION exactly as it binds
    // DIAGNOSTIC_SELECTION_V3: nothing about this type short-circuits, blocks, or special-cases
    // the resolution -- it produces the same ProbeResolution shape, through the same method, as
    // ROOT_CAUSE_PROBE does for a target with the same items and exposure.
    ProbeTargetObjective target = new ProbeTargetObjective(TARGET_OBJECTIVE, null);
    ProbeCandidateItem item = candidate();

    ProbeResolution prerequisiteValidation = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.PREREQUISITE_VALIDATION,
        target, List.of(item), Set.of());
    ProbeResolution rootCauseProbe = ProbeRelationshipResolver.resolve(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        target, List.of(item), Set.of());

    assertThat(prerequisiteValidation.outcome()).isEqualTo(rootCauseProbe.outcome());
    assertThat(prerequisiteValidation.candidates()).isEqualTo(rootCauseProbe.candidates());
  }

  // -----------------------------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------------------------

  private static ProbeCandidateItem candidate() {
    return new ProbeCandidateItem(UUID.randomUUID(), UUID.randomUUID());
  }
}
