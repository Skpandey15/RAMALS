package io.ramals.learningplatform.assessment.misconceptiongraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * M2-ADR-033 step 2: {@link MisconceptionGraphQueryService} orchestration, over a mocked
 * repository -- the bounded query count, the empty-state contract, deterministic composition, and
 * that not-found is distinct from empty. The SQL itself is covered by
 * {@code MisconceptionGraphQuerySurfaceIntegrationTests} against real PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class MisconceptionGraphQueryServiceTests {

  @Mock private MisconceptionGraphQueryRepository repository;

  private static final UUID OBJECTIVE_ID = UUID.fromString("01900000-0000-7000-8000-0000000000d1");
  private static final UUID OWNING_SKILL = UUID.fromString("01900000-0000-7000-8000-000000000107");
  private static final UUID CURRICULUM_VERSION =
      UUID.fromString("01900000-0000-7000-8000-000000000004");

  private MisconceptionGraphQueryService service() {
    return new MisconceptionGraphQueryService(repository);
  }

  private GraphTargetView objectiveTargetView() {
    return new GraphTargetView(
        MisconceptionTargetType.LEARNING_OBJECTIVE, OBJECTIVE_ID, "ACK_DURABILITY", OBJECTIVE_ID,
        OWNING_SKILL, "KAFKA_PRODUCER_ACKS", CURRICULUM_VERSION, "KAFKA");
  }

  private PublishedMisconceptionView misconception(UUID id, String name) {
    return new PublishedMisconceptionView(
        id, name, "desc", MisconceptionTargetType.LEARNING_OBJECTIVE, OBJECTIVE_ID);
  }

  // -- not-found is deterministic and distinct from empty --------------------------------------

  @Test
  @DisplayName("an unknown target throws MisconceptionGraphTargetNotFoundException")
  void unknownTargetThrows() {
    MisconceptionGraphTarget target =
        new MisconceptionGraphTarget(MisconceptionTargetType.CONCEPT, OBJECTIVE_ID);
    when(repository.resolveTarget(target)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().graphFor(target))
        .isInstanceOf(MisconceptionGraphTargetNotFoundException.class);

    verify(repository).resolveTarget(target);
    verifyNoMoreInteractions(repository);
  }

  // -- empty-state semantics -----------------------------------------------------------------------

  @Test
  @DisplayName("a valid target with no published misconceptions returns a successful empty view")
  void validTargetNoMisconceptionsReturnsEmptyView() {
    MisconceptionGraphTarget target =
        new MisconceptionGraphTarget(MisconceptionTargetType.LEARNING_OBJECTIVE, OBJECTIVE_ID);
    when(repository.resolveTarget(target)).thenReturn(Optional.of(objectiveTargetView()));
    when(repository.findCurriculumPrerequisites(OWNING_SKILL, CURRICULUM_VERSION))
        .thenReturn(List.of(new CurriculumPrerequisiteView(
            UUID.fromString("01900000-0000-7000-8000-000000000101"), "KAFKA_BROKER")));
    when(repository.findPublishedMisconceptionsForTarget(target)).thenReturn(List.of());

    MisconceptionGraphView view = service().graphFor(target);

    assertThat(view.target()).isEqualTo(objectiveTargetView());
    assertThat(view.prerequisites()).extracting(CurriculumPrerequisiteView::prerequisiteSkillCode)
        .containsExactly("KAFKA_BROKER");
    assertThat(view.misconceptions()).isEmpty();
    assertThat(view.relationships()).isEmpty();
    assertThat(view.prerequisiteLinks()).isEmpty();

    // Q4 and Q5 are never issued when there are no misconceptions -- 3 statements, not 5.
    verify(repository).resolveTarget(target);
    verify(repository).findCurriculumPrerequisites(OWNING_SKILL, CURRICULUM_VERSION);
    verify(repository).findPublishedMisconceptionsForTarget(target);
    verify(repository, never()).findPublishedRelationshipsForMisconceptions(any());
    verify(repository, never()).findPublishedPrerequisiteLinksForMisconceptions(any());
    verifyNoMoreInteractions(repository);
  }

  @Test
  @DisplayName("a target with misconceptions but no edges still returns a valid populated view")
  void misconceptionsButNoEdges() {
    MisconceptionGraphTarget target =
        new MisconceptionGraphTarget(MisconceptionTargetType.LEARNING_OBJECTIVE, OBJECTIVE_ID);
    UUID m1 = UUID.randomUUID();
    when(repository.resolveTarget(target)).thenReturn(Optional.of(objectiveTargetView()));
    when(repository.findCurriculumPrerequisites(any(), any())).thenReturn(List.of());
    when(repository.findPublishedMisconceptionsForTarget(target))
        .thenReturn(List.of(misconception(m1, "only one")));
    when(repository.findPublishedRelationshipsForMisconceptions(Set.of(m1))).thenReturn(List.of());
    when(repository.findPublishedPrerequisiteLinksForMisconceptions(Set.of(m1))).thenReturn(List.of());

    MisconceptionGraphView view = service().graphFor(target);

    assertThat(view.misconceptions()).extracting(PublishedMisconceptionView::id).containsExactly(m1);
    assertThat(view.relationships()).isEmpty();
    assertThat(view.prerequisiteLinks()).isEmpty();
  }

  // -- bounded query count: constant in the number of misconceptions --------------------------

  @Test
  @DisplayName("the query count is 5 regardless of how many misconceptions the node has")
  void queryCountIsConstantInMisconceptionCount() {
    MisconceptionGraphTarget target =
        new MisconceptionGraphTarget(MisconceptionTargetType.SUB_CONCEPT, OBJECTIVE_ID);
    when(repository.resolveTarget(target)).thenReturn(Optional.of(objectiveTargetView()));
    when(repository.findCurriculumPrerequisites(any(), any())).thenReturn(List.of());

    List<PublishedMisconceptionView> twenty = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      twenty.add(misconception(UUID.randomUUID(), "m" + String.format("%02d", i)));
    }
    when(repository.findPublishedMisconceptionsForTarget(target)).thenReturn(twenty);
    when(repository.findPublishedRelationshipsForMisconceptions(any())).thenReturn(List.of());
    when(repository.findPublishedPrerequisiteLinksForMisconceptions(any())).thenReturn(List.of());

    service().graphFor(target);

    // Exactly one call to each read method -- never one relationship/link query per misconception.
    verify(repository, times(1)).resolveTarget(target);
    verify(repository, times(1)).findCurriculumPrerequisites(any(), any());
    verify(repository, times(1)).findPublishedMisconceptionsForTarget(target);
    verify(repository, times(1)).findPublishedRelationshipsForMisconceptions(any());
    verify(repository, times(1)).findPublishedPrerequisiteLinksForMisconceptions(any());
    verifyNoMoreInteractions(repository);
  }

  @Test
  @DisplayName("the full misconception id set is handed to Q4 and Q5 in one call each")
  void theWholeIdSetIsBulkMatched() {
    MisconceptionGraphTarget target =
        new MisconceptionGraphTarget(MisconceptionTargetType.CONCEPT, OBJECTIVE_ID);
    UUID m1 = UUID.randomUUID();
    UUID m2 = UUID.randomUUID();
    UUID m3 = UUID.randomUUID();
    when(repository.resolveTarget(target)).thenReturn(Optional.of(objectiveTargetView()));
    when(repository.findCurriculumPrerequisites(any(), any())).thenReturn(List.of());
    when(repository.findPublishedMisconceptionsForTarget(target)).thenReturn(
        List.of(misconception(m1, "a"), misconception(m2, "b"), misconception(m3, "c")));
    when(repository.findPublishedRelationshipsForMisconceptions(any())).thenReturn(List.of());
    when(repository.findPublishedPrerequisiteLinksForMisconceptions(any())).thenReturn(List.of());

    service().graphFor(target);

    ArgumentCaptor<Set<UUID>> relCaptor = ArgumentCaptor.forClass(Set.class);
    ArgumentCaptor<Set<UUID>> linkCaptor = ArgumentCaptor.forClass(Set.class);
    verify(repository).findPublishedRelationshipsForMisconceptions(relCaptor.capture());
    verify(repository).findPublishedPrerequisiteLinksForMisconceptions(linkCaptor.capture());
    assertThat(relCaptor.getValue()).containsExactlyInAnyOrder(m1, m2, m3);
    assertThat(linkCaptor.getValue()).containsExactlyInAnyOrder(m1, m2, m3);
  }

  // -- deterministic composition ------------------------------------------------------------------

  @Test
  @DisplayName("the same repository state composes an equal view every time")
  void compositionIsDeterministic() {
    MisconceptionGraphTarget target =
        new MisconceptionGraphTarget(MisconceptionTargetType.LEARNING_OBJECTIVE, OBJECTIVE_ID);
    UUID m1 = UUID.fromString("01900000-0000-7000-8000-000000000f03");
    UUID m2 = UUID.fromString("01900000-0000-7000-8000-000000000f04");
    when(repository.resolveTarget(target)).thenReturn(Optional.of(objectiveTargetView()));
    when(repository.findCurriculumPrerequisites(any(), any())).thenReturn(
        List.of(new CurriculumPrerequisiteView(UUID.randomUUID(), "KAFKA_BROKER")));
    when(repository.findPublishedMisconceptionsForTarget(target)).thenReturn(
        List.of(misconception(m1, "a"), misconception(m2, "b")));
    when(repository.findPublishedRelationshipsForMisconceptions(any())).thenReturn(List.of(
        new PublishedMisconceptionRelationshipView(
            UUID.randomUUID(), MisconceptionRelatedType.CONTRASTS_WITH, m1, m2, true, true, true,
            null, "why", Instant.parse("2026-01-01T00:00:00Z"))));
    when(repository.findPublishedPrerequisiteLinksForMisconceptions(any())).thenReturn(List.of());

    MisconceptionGraphView first = service().graphFor(target);
    MisconceptionGraphView second = service().graphFor(target);

    assertThat(first).isEqualTo(second);
    assertThat(first.relationships()).isEqualTo(second.relationships());
  }

  @Test
  @DisplayName("prerequisites are still returned for a CONCEPT target with no misconceptions")
  void prerequisitesForConceptWithNoMisconceptions() {
    MisconceptionGraphTarget target =
        new MisconceptionGraphTarget(MisconceptionTargetType.CONCEPT, OBJECTIVE_ID);
    when(repository.resolveTarget(target)).thenReturn(Optional.of(new GraphTargetView(
        MisconceptionTargetType.CONCEPT, OBJECTIVE_ID, "ACK durability concept", OBJECTIVE_ID,
        OWNING_SKILL, "KAFKA_PRODUCER_ACKS", CURRICULUM_VERSION, "KAFKA")));
    when(repository.findCurriculumPrerequisites(eq(OWNING_SKILL), eq(CURRICULUM_VERSION)))
        .thenReturn(List.of(new CurriculumPrerequisiteView(UUID.randomUUID(), "KAFKA_BROKER")));
    when(repository.findPublishedMisconceptionsForTarget(target)).thenReturn(List.of());

    MisconceptionGraphView view = service().graphFor(target);

    assertThat(view.prerequisites()).hasSize(1);
    assertThat(view.misconceptions()).isEmpty();
  }
}
