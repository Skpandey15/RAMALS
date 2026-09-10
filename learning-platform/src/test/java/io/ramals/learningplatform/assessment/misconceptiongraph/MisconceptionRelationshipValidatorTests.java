package io.ramals.learningplatform.assessment.misconceptiongraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.assessment.misconceptiongraph.MisconceptionRelationshipRepository.PrerequisiteCheck;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@link MisconceptionRelationshipReasonCode} the deterministic validator can raise, plus the
 * neighbouring accepted case, so a rule that stops discriminating fails here. The repository is
 * mocked -- these tests are about the validator's rules, not the database's.
 */
class MisconceptionRelationshipValidatorTests {

  private static final UUID A = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID B = UUID.fromString("01900000-0000-7000-8000-0000000000b2");
  private static final UUID SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");

  private MisconceptionRelationshipRepository repository;
  private MisconceptionRelationshipValidator validator;

  @BeforeEach
  void setUp() {
    repository = mock(MisconceptionRelationshipRepository.class);
    validator = new MisconceptionRelationshipValidator(repository);
    // Default: both endpoints exist and are PUBLISHED, nothing is a duplicate, no cycle.
    lenient().when(repository.misconceptionStatus(any()))
        .thenReturn(Optional.of(MisconceptionGraphStatus.PUBLISHED));
    lenient().when(repository.skillExists(any())).thenReturn(true);
    lenient().when(repository.relationshipExists(any(), any())).thenReturn(false);
    lenient().when(repository.prerequisiteLinkExists(any(), any())).thenReturn(false);
    lenient().when(repository.wouldSpecialisationCreateCycle(any(), any())).thenReturn(false);
    lenient().when(repository.checkCurriculumPrerequisite(any(), any()))
        .thenReturn(PrerequisiteCheck.MATCH);
  }

  private static MisconceptionRelationshipReasonCode reasonOf(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
    try {
      callable.call();
    } catch (MisconceptionGraphValidationException refused) {
      return refused.reasonCode();
    } catch (Throwable other) {
      throw new AssertionError("expected a validation refusal, got " + other, other);
    }
    throw new AssertionError("expected a validation refusal, none thrown");
  }

  // -- MISCONCEPTION_RELATED: required fields ---------------------------------------------------

  @Test
  void nullSourceIsSourceRequired() {
    assertThat(reasonOf(() -> validator.validateRelationship(
        null, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.SOURCE_REQUIRED);
  }

  @Test
  void nullTargetIsTargetRequired() {
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, null, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.TARGET_REQUIRED);
  }

  @Test
  void nullTypeIsRelationshipTypeRequired() {
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, B, null, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.RELATIONSHIP_TYPE_REQUIRED);
  }

  @Test
  void blankRationaleIsRationaleRequired() {
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "   ", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.RATIONALE_REQUIRED);
  }

  // -- self, not-found, duplicate ---------------------------------------------------------------

  @Test
  @DisplayName("A RELATED A is rejected deterministically, never canonicalised away")
  void selfEdgeIsRejected() {
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, A, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.SELF_RELATIONSHIP_NOT_ALLOWED);
  }

  @Test
  void unknownSourceMisconception() {
    when(repository.misconceptionStatus(A)).thenReturn(Optional.empty());
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.SOURCE_MISCONCEPTION_NOT_FOUND);
  }

  @Test
  void unknownTargetMisconception() {
    when(repository.misconceptionStatus(B)).thenReturn(Optional.empty());
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.TARGET_MISCONCEPTION_NOT_FOUND);
  }

  @Test
  void duplicateRelatedEdgeIsRejected() {
    when(repository.relationshipExists(any(), eq(MisconceptionRelatedType.CO_OCCURS_WITH)))
        .thenReturn(true);
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.DUPLICATE_RELATIONSHIP);
  }

  // -- symmetry / canonicalisation -----------------------------------------------------------

  @Test
  @DisplayName("a symmetric edge is checked and stored under a canonical (a<b) ordering")
  void symmetricEdgeIsCanonicalisedBeforeTheDuplicateCheck() {
    // Author B->A; the duplicate check and the returned pair must both use canonical A,B.
    var captured = new java.util.concurrent.atomic.AtomicReference<CanonicalMisconceptionPair>();
    when(repository.relationshipExists(any(), any())).thenAnswer(invocation -> {
      captured.set(invocation.getArgument(0));
      return false;
    });

    CanonicalMisconceptionPair pair = validator.validateRelationship(
        B, A, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT);

    assertThat(pair.isOrdered()).isTrue();
    assertThat(pair.a()).isEqualTo(A);
    assertThat(pair.b()).isEqualTo(B);
    assertThat(captured.get().a()).isEqualTo(A);
  }

  @Test
  @DisplayName("SPECIALISES keeps its authored direction; the reverse is a distinct edge")
  void specialisesKeepsDirection() {
    CanonicalMisconceptionPair forward = validator.validateRelationship(
        A, B, MisconceptionRelatedType.SPECIALISES, "a is a kind of b",
        MisconceptionGraphStatus.DRAFT);
    assertThat(forward.a()).isEqualTo(A);
    assertThat(forward.b()).isEqualTo(B);

    CanonicalMisconceptionPair reverse = validator.validateRelationship(
        B, A, MisconceptionRelatedType.SPECIALISES, "b is a kind of a",
        MisconceptionGraphStatus.DRAFT);
    assertThat(reverse.a()).isEqualTo(B);
    assertThat(reverse.b()).isEqualTo(A);
  }

  // -- cycle -------------------------------------------------------------------------------------

  @Test
  void specialisesCycleIsRejected() {
    when(repository.wouldSpecialisationCreateCycle(A, B)).thenReturn(true);
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, B, MisconceptionRelatedType.SPECIALISES, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.SPECIALISES_CYCLE_NOT_ALLOWED);
  }

  @Test
  @DisplayName("a symmetric type is never subject to the cycle check")
  void symmetricTypesHaveNoCycleConcept() {
    when(repository.wouldSpecialisationCreateCycle(any(), any())).thenReturn(true);
    // CO_OCCURS_WITH must still be accepted -- the cycle check only applies to SPECIALISES.
    assertThat(validator.validateRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT))
        .isNotNull();
  }

  // -- publication --------------------------------------------------------------------------------

  @Test
  void aPublishedEdgeMayNotReferenceADraftEndpoint() {
    when(repository.misconceptionStatus(B))
        .thenReturn(Optional.of(MisconceptionGraphStatus.DRAFT));
    assertThat(reasonOf(() -> validator.validateRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.PUBLISHED)))
        .isEqualTo(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
  }

  @Test
  void aDraftEdgeMayReferenceADraftEndpoint() {
    when(repository.misconceptionStatus(B))
        .thenReturn(Optional.of(MisconceptionGraphStatus.DRAFT));
    assertThat(validator.validateRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why", MisconceptionGraphStatus.DRAFT))
        .isNotNull();
  }

  @Test
  void republishValidationSkipsTheDuplicateCheckButKeepsThePublishedEndpointRule() {
    var edge = new MisconceptionRelationship(
        UUID.randomUUID(), A, B, MisconceptionRelatedType.CO_OCCURS_WITH,
        MisconceptionGraphStatus.DRAFT, "why", java.time.Instant.now(), null);

    // The edge itself matches relationshipExists, but publish validation must not treat that as a
    // duplicate.
    when(repository.relationshipExists(any(), any())).thenReturn(true);
    validator.validateRelationshipPublish(edge); // no throw

    when(repository.misconceptionStatus(A))
        .thenReturn(Optional.of(MisconceptionGraphStatus.DRAFT));
    assertThat(reasonOf(() -> validator.validateRelationshipPublish(edge)))
        .isEqualTo(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
  }

  // -- MISCONCEPTION_PREREQUISITE_LINK -------------------------------------------------------

  @Test
  void prerequisiteLinkRequiredFields() {
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        null, SKILL, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.SOURCE_REQUIRED);
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        A, null, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.TARGET_REQUIRED);
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        A, SKILL, " ", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.RATIONALE_REQUIRED);
  }

  @Test
  void unknownMisconceptionOrSkillOnAPrerequisiteLink() {
    when(repository.misconceptionStatus(A)).thenReturn(Optional.empty());
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        A, SKILL, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.SOURCE_MISCONCEPTION_NOT_FOUND);

    when(repository.misconceptionStatus(A))
        .thenReturn(Optional.of(MisconceptionGraphStatus.PUBLISHED));
    when(repository.skillExists(SKILL)).thenReturn(false);
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        A, SKILL, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.PREREQUISITE_SKILL_NOT_FOUND);
  }

  @Test
  void duplicatePrerequisiteLinkIsRejected() {
    when(repository.prerequisiteLinkExists(A, SKILL)).thenReturn(true);
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        A, SKILL, "why", MisconceptionGraphStatus.DRAFT)))
        .isEqualTo(MisconceptionRelationshipReasonCode.DUPLICATE_RELATIONSHIP);
  }

  @Test
  @DisplayName("a DRAFT prerequisite link is not yet held to the curriculum-prerequisite rule")
  void draftPrerequisiteLinkDoesNotRunTheCurriculumCheck() {
    when(repository.checkCurriculumPrerequisite(any(), any()))
        .thenReturn(PrerequisiteCheck.NOT_A_PREREQUISITE);
    // No throw: the curriculum check is a publish-time rule.
    validator.validatePrerequisiteLink(A, SKILL, "why", MisconceptionGraphStatus.DRAFT);
  }

  @Test
  void publishingAPrerequisiteLinkToANonPrerequisiteSkillIsRejected() {
    when(repository.checkCurriculumPrerequisite(A, SKILL))
        .thenReturn(PrerequisiteCheck.NOT_A_PREREQUISITE);
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        A, SKILL, "why", MisconceptionGraphStatus.PUBLISHED)))
        .isEqualTo(MisconceptionRelationshipReasonCode
            .PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE);
  }

  @Test
  void publishingAPrerequisiteLinkForAnUnresolvableOwningSkillIsRejected() {
    when(repository.checkCurriculumPrerequisite(A, SKILL))
        .thenReturn(PrerequisiteCheck.OWNING_SKILL_UNRESOLVABLE);
    assertThat(reasonOf(() -> validator.validatePrerequisiteLink(
        A, SKILL, "why", MisconceptionGraphStatus.PUBLISHED)))
        .isEqualTo(MisconceptionRelationshipReasonCode.MISCONCEPTION_OWNING_SKILL_UNRESOLVABLE);
  }

  @Test
  void publishingAWellFormedPrerequisiteLinkIsAccepted() {
    validator.validatePrerequisiteLink(A, SKILL, "why", MisconceptionGraphStatus.PUBLISHED);
  }
}
