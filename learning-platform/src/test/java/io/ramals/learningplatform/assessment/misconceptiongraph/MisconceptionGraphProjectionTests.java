package io.ramals.learningplatform.assessment.misconceptiongraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-033 step 2 (prompt §15/§16/§6): the {@link MisconceptionGraphView} contract carries only
 * authored knowledge. No component anywhere in the projection tree names a learner, an evidence
 * observation, a mastery / confidence / probability / posterior value, a ranking score, or an
 * adaptive weight -- there is nowhere in the returned type to put one. The projection is also
 * structurally immutable.
 */
class MisconceptionGraphProjectionTests {

  /** Substrings that must never appear in a projection component name (case-insensitive). */
  private static final List<String> FORBIDDEN = List.of(
      "learner", "mastery", "confidence", "probability", "posterior", "evidence", "observation",
      "score", "ranking", "weight", "band", "entropy", "informationgain", "attempt", "interaction");

  private static final Set<Class<?>> LEAF_TYPES = Set.of(
      UUID.class, String.class, Instant.class, boolean.class, Boolean.class, int.class,
      Integer.class, long.class, Long.class, MisconceptionTargetType.class,
      MisconceptionRelatedType.class, MisconceptionGraphStatus.class);

  @Test
  @DisplayName("no projection component names a learner-scoped or derived-inference field")
  void projectionTreeHasNoLearnerOrInferenceField() {
    List<String> offenders = new ArrayList<>();
    Deque<Class<?>> queue = new ArrayDeque<>(List.of(MisconceptionGraphView.class));
    Set<Class<?>> seen = new HashSet<>();

    while (!queue.isEmpty()) {
      Class<?> type = queue.poll();
      if (!seen.add(type) || !type.isRecord()) {
        continue;
      }
      for (RecordComponent component : type.getRecordComponents()) {
        String name = component.getName().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN) {
          if (name.contains(forbidden)) {
            offenders.add(type.getSimpleName() + "." + component.getName());
          }
        }
        Class<?> componentType = component.getType();
        Class<?> element = List.class.isAssignableFrom(componentType)
            ? elementType(component)
            : componentType;
        if (element != null && !LEAF_TYPES.contains(element)) {
          queue.add(element);
        }
      }
    }

    assertThat(offenders)
        .as("M2-ADR-033 §4: the authored graph view exposes no learner-scoped or inferred field")
        .isEmpty();
  }

  private static Class<?> elementType(RecordComponent component) {
    String generic = component.getGenericType().getTypeName();
    int open = generic.indexOf('<');
    int close = generic.lastIndexOf('>');
    if (open < 0 || close < 0) {
      return null;
    }
    try {
      return Class.forName(generic.substring(open + 1, close));
    } catch (ClassNotFoundException notFound) {
      return null;
    }
  }

  @Test
  @DisplayName("the view's list components are copied and unmodifiable")
  void listComponentsAreUnmodifiable() {
    GraphTargetView target = new GraphTargetView(
        MisconceptionTargetType.CONCEPT, UUID.randomUUID(), "c", UUID.randomUUID(),
        UUID.randomUUID(), "SKILL", UUID.randomUUID(), "DOMAIN");

    List<PublishedMisconceptionView> mutableMisconceptions = new ArrayList<>();
    mutableMisconceptions.add(new PublishedMisconceptionView(
        UUID.randomUUID(), "m", "d", MisconceptionTargetType.CONCEPT, target.id()));

    MisconceptionGraphView view = new MisconceptionGraphView(
        target, new ArrayList<>(), mutableMisconceptions, new ArrayList<>(), new ArrayList<>());

    // Defensive copy: mutating the source list afterwards does not change the view.
    mutableMisconceptions.clear();
    assertThat(view.misconceptions()).hasSize(1);

    assertThatThrownBy(() -> view.misconceptions().add(view.misconceptions().get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> view.relationships().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("SPECIALISES carries a GENERALISES reverse-reading label; symmetric types do not")
  void reverseReadingLabelIsAReadInterpretationOnly() {
    PublishedMisconceptionRelationshipView specialises = new PublishedMisconceptionRelationshipView(
        UUID.randomUUID(), MisconceptionRelatedType.SPECIALISES, UUID.randomUUID(), UUID.randomUUID(),
        true, true, false, "GENERALISES", "why", Instant.now());
    PublishedMisconceptionRelationshipView contrasts = new PublishedMisconceptionRelationshipView(
        UUID.randomUUID(), MisconceptionRelatedType.CONTRASTS_WITH, UUID.randomUUID(),
        UUID.randomUUID(), true, true, true, null, "why", Instant.now());

    assertThat(specialises.reverseReadingLabel()).isEqualTo("GENERALISES");
    assertThat(specialises.symmetric()).isFalse();
    assertThat(contrasts.reverseReadingLabel()).isNull();
    assertThat(contrasts.symmetric()).isTrue();
    // The stored type is only ever one of the three ADR sub-types -- GENERALISES is never stored.
    assertThat(MisconceptionRelatedType.values())
        .noneMatch(type -> type.name().equals("GENERALISES"));
  }
}
