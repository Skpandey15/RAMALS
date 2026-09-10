package io.ramals.learningplatform.assessment.misconceptiongraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Canonical storage ordering for symmetric vs directed related edges (M2-ADR-033 §1). */
class CanonicalMisconceptionPairTests {

  private static final UUID LOW = UUID.fromString("01900000-0000-7000-8000-00000000000a");
  private static final UUID HIGH = UUID.fromString("01900000-0000-7000-8000-0000000000f0");

  @Test
  @DisplayName("a symmetric type is stored low-id -> high-id regardless of authored order")
  void symmetricIsOrdered() {
    for (MisconceptionRelatedType type :
        new MisconceptionRelatedType[] {
            MisconceptionRelatedType.CO_OCCURS_WITH, MisconceptionRelatedType.CONTRASTS_WITH}) {
      CanonicalMisconceptionPair fromLow = CanonicalMisconceptionPair.of(type, LOW, HIGH);
      CanonicalMisconceptionPair fromHigh = CanonicalMisconceptionPair.of(type, HIGH, LOW);
      assertThat(fromLow).isEqualTo(fromHigh);
      assertThat(fromLow.a()).isEqualTo(LOW);
      assertThat(fromLow.b()).isEqualTo(HIGH);
      assertThat(fromLow.isOrdered()).isTrue();
    }
  }

  @Test
  @DisplayName("a directed type keeps its authored source -> target order")
  void directedKeepsAuthoredOrder() {
    CanonicalMisconceptionPair forward =
        CanonicalMisconceptionPair.of(MisconceptionRelatedType.SPECIALISES, HIGH, LOW);
    assertThat(forward.a()).isEqualTo(HIGH);
    assertThat(forward.b()).isEqualTo(LOW);
    // The reverse authored direction is a different (and, for the DB, allowed) edge.
    CanonicalMisconceptionPair reverse =
        CanonicalMisconceptionPair.of(MisconceptionRelatedType.SPECIALISES, LOW, HIGH);
    assertThat(reverse).isNotEqualTo(forward);
  }

  @Test
  @DisplayName("ordering uses unsigned lexical UUID order, matching PostgreSQL's uuid type")
  void orderingMatchesPostgresUuidOrder() {
    // A UUID whose most-significant bit is set: signed UUID.compareTo would call it 'smaller',
    // PostgreSQL and the canonical string ordering call it 'larger'.
    UUID highBitSet = UUID.fromString(" f0000000-0000-7000-8000-000000000000".trim());
    CanonicalMisconceptionPair pair =
        CanonicalMisconceptionPair.of(MisconceptionRelatedType.CO_OCCURS_WITH, highBitSet, LOW);
    assertThat(pair.a()).isEqualTo(LOW);
    assertThat(pair.b()).isEqualTo(highBitSet);
    assertThat(pair.isOrdered()).isTrue();
  }
}
