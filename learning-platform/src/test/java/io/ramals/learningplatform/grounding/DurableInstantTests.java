package io.ramals.learningplatform.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the canonicalization itself. The behaviour that matters at the persistence
 * boundary is proven against real PostgreSQL in {@code
 * GroundingIdentityDurablePrecisionIntegrationTests}; these assertions pin the contract the
 * canonicalization has to satisfy for that round trip to be possible at all.
 */
class DurableInstantTests {

  @Test
  void canonicalizationMatchesPostgresTimestamptzPrecision() {
    assertThat(DurableInstant.PRECISION).isEqualTo(ChronoUnit.MICROS);
    assertThat(DurableInstant.canonical(Instant.parse("2026-08-26T06:15:50.751584123Z")))
        .isEqualTo(Instant.parse("2026-08-26T06:15:50.751584Z"));
  }

  /**
   * Idempotence is the whole point: a value that has already been through the database must survive
   * a second canonicalization untouched, or reconstruction could never agree with the original.
   */
  @Test
  void canonicalizationIsIdempotent() {
    Instant once = DurableInstant.canonical(Instant.parse("2026-08-26T06:15:50.751584123Z"));
    assertThat(DurableInstant.canonical(once)).isEqualTo(once);
    Instant alreadyCanonical = Instant.parse("2026-08-26T06:15:50.751584Z");
    assertThat(DurableInstant.canonical(alreadyCanonical)).isEqualTo(alreadyCanonical);
  }

  @Test
  void canonicalizationTruncatesRatherThanRounds() {
    // Rounding would move an instant forward past a boundary the store never observed.
    assertThat(DurableInstant.canonical(Instant.parse("2026-08-26T06:15:50.751584999Z")))
        .isEqualTo(Instant.parse("2026-08-26T06:15:50.751584Z"));
  }

  @Test
  void differencesAboveThePrecisionAreKept() {
    Instant earlier = Instant.parse("2026-08-26T06:15:50.751584Z");
    Instant later = Instant.parse("2026-08-26T06:15:50.751585Z");
    assertThat(DurableInstant.canonical(earlier)).isNotEqualTo(DurableInstant.canonical(later));
  }

  @Test
  void nullPassesThroughSoCallersKeepTheirOwnNullability() {
    assertThat(DurableInstant.canonical(null)).isNull();
  }

  @Test
  void instantsBeforeTheEpochTruncateTowardsTheEpochConsistently() {
    // truncatedTo is defined towards the epoch, so a pre-1970 value moves forward in time. The
    // property that matters is that it is stable and matches what the column returns.
    Instant preEpoch = Instant.parse("1969-12-31T23:59:59.123456789Z");
    Instant canonical = DurableInstant.canonical(preEpoch);
    assertThat(DurableInstant.canonical(canonical)).isEqualTo(canonical);
    assertThat(canonical.getNano() % 1_000).isZero();
  }
}
