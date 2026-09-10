package io.ramals.learningplatform.assessment.misconceptiongraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * The service's own responsibilities: it runs validation before persistence, translates a database
 * constraint/trigger violation from the concurrency race window into the same stable reason code,
 * and publishes idempotently. Both collaborators are mocked.
 */
class MisconceptionRelationshipServiceTests {

  private static final UUID A = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID B = UUID.fromString("01900000-0000-7000-8000-0000000000b2");
  private static final UUID SKILL = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID EDGE_ID = UUID.fromString("01900000-0000-7000-8000-0000000000e1");

  private MisconceptionRelationshipRepository repository;
  private MisconceptionRelationshipValidator validator;
  private MisconceptionRelationshipService service;

  @BeforeEach
  void setUp() {
    repository = mock(MisconceptionRelationshipRepository.class);
    validator = mock(MisconceptionRelationshipValidator.class);
    service = new MisconceptionRelationshipService(repository, validator);
    when(validator.validateRelationship(any(), any(), any(), any(), any()))
        .thenReturn(new CanonicalMisconceptionPair(A, B));
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

  // -- validate-then-persist ordering -------------------------------------------------------------

  @Test
  void validationRunsBeforeAnyInsert() {
    when(validator.validateRelationship(any(), any(), any(), any(), any()))
        .thenThrow(new MisconceptionGraphValidationException(
            MisconceptionRelationshipReasonCode.SELF_RELATIONSHIP_NOT_ALLOWED));

    assertThatThrownBy(() -> service.authorDraftRelationship(
        A, A, MisconceptionRelatedType.CO_OCCURS_WITH, "why"))
        .isInstanceOf(MisconceptionGraphValidationException.class);

    verify(repository, never()).insertDraftRelationship(any(), any(), any());
  }

  @Test
  void aValidDraftRelationshipIsPersistedWithItsCanonicalPair() {
    when(repository.insertDraftRelationship(any(), any(), any())).thenReturn(EDGE_ID);
    UUID id = service.authorDraftRelationship(
        B, A, MisconceptionRelatedType.CO_OCCURS_WITH, "meaningfully related");
    assertThat(id).isEqualTo(EDGE_ID);
    verify(repository).insertDraftRelationship(
        eqPair(A, B), org.mockito.ArgumentMatchers.eq(MisconceptionRelatedType.CO_OCCURS_WITH),
        any());
  }

  private static CanonicalMisconceptionPair eqPair(UUID a, UUID b) {
    return org.mockito.ArgumentMatchers.argThat(
        pair -> pair != null && pair.a().equals(a) && pair.b().equals(b));
  }

  // -- database-race translation ---------------------------------------------------------------

  @Test
  @DisplayName("a unique-constraint race is DUPLICATE_RELATIONSHIP, not a raw DataAccessException")
  void uniqueViolationBecomesDuplicateReason() {
    when(repository.insertDraftRelationship(any(), any(), any()))
        .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint"));
    assertThat(reasonOf(() -> service.authorDraftRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why")))
        .isEqualTo(MisconceptionRelationshipReasonCode.DUPLICATE_RELATIONSHIP);
  }

  @Test
  void immutabilityTriggerBecomesImmutableReason() {
    when(repository.insertDraftRelationship(any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException(
            "published misconception relationship abc is immutable"));
    assertThat(reasonOf(() -> service.authorDraftRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why")))
        .isEqualTo(MisconceptionRelationshipReasonCode.IMMUTABLE_PUBLISHED_RELATIONSHIP);
  }

  @Test
  void cycleTriggerBecomesCycleReason() {
    when(repository.insertDraftRelationship(any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException(
            "SPECIALISES cycle detected: x already specialises (transitively) y"));
    assertThat(reasonOf(() -> service.authorDraftRelationship(
        A, B, MisconceptionRelatedType.SPECIALISES, "why")))
        .isEqualTo(MisconceptionRelationshipReasonCode.SPECIALISES_CYCLE_NOT_ALLOWED);
  }

  @Test
  void publishedEndpointTriggerBecomesPublishedEndpointReason() {
    when(repository.insertDraftRelationship(any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException(
            "a published misconception relationship may reference only published misconceptions"));
    assertThat(reasonOf(() -> service.authorDraftRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why")))
        .isEqualTo(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
  }

  @Test
  void prerequisiteTriggerBecomesPrerequisiteReason() {
    when(repository.insertDraftPrerequisiteLink(any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException(
            "skill x is not a curriculum prerequisite of misconception y"));
    assertThat(reasonOf(() -> service.authorDraftPrerequisiteLink(A, SKILL, "why")))
        .isEqualTo(MisconceptionRelationshipReasonCode
            .PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE);
  }

  @Test
  @DisplayName("an unrecognised integrity violation is rethrown unchanged, never mislabelled")
  void unrecognisedViolationIsNotSwallowed() {
    var raw = new DataIntegrityViolationException("some unrelated constraint");
    when(repository.insertDraftRelationship(any(), any(), any())).thenThrow(raw);
    assertThatThrownBy(() -> service.authorDraftRelationship(
        A, B, MisconceptionRelatedType.CO_OCCURS_WITH, "why"))
        .isSameAs(raw);
  }

  // -- publish -------------------------------------------------------------------------------------

  @Test
  void publishOfAMissingEdgeIsNotFound() {
    when(repository.findRelationshipById(EDGE_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.publishRelationship(EDGE_ID))
        .isInstanceOf(MisconceptionGraphEdgeNotFoundException.class);
  }

  @Test
  @DisplayName("publishing an already-published edge is an idempotent no-op")
  void publishIsIdempotent() {
    var published = new MisconceptionRelationship(
        EDGE_ID, A, B, MisconceptionRelatedType.CO_OCCURS_WITH,
        MisconceptionGraphStatus.PUBLISHED, "why", Instant.now(), Instant.now());
    when(repository.findRelationshipById(EDGE_ID)).thenReturn(Optional.of(published));

    service.publishRelationship(EDGE_ID);

    verify(repository, never()).publishRelationship(any());
    verify(validator, never()).validateRelationshipPublish(any());
  }

  @Test
  void publishOfADraftEdgeReValidatesThenUpdates() {
    var draft = new MisconceptionRelationship(
        EDGE_ID, A, B, MisconceptionRelatedType.CO_OCCURS_WITH,
        MisconceptionGraphStatus.DRAFT, "why", Instant.now(), null);
    when(repository.findRelationshipById(EDGE_ID)).thenReturn(Optional.of(draft));

    service.publishRelationship(EDGE_ID);

    verify(validator).validateRelationshipPublish(draft);
    verify(repository).publishRelationship(EDGE_ID);
  }
}
