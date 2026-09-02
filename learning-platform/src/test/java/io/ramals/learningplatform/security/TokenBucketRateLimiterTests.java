package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTests {

  private static RateLimitProperties properties(int capacity, double refillPerSecond) {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setCapacity(capacity);
    properties.setRefillPerSecond(refillPerSecond);
    return properties;
  }

  private static RateLimitProperties properties(int capacity, double refillPerSecond, int maxBuckets) {
    RateLimitProperties properties = properties(capacity, refillPerSecond);
    properties.setMaxBuckets(maxBuckets);
    return properties;
  }

  @Test
  void deniesWhenExhaustedAndRecoversAfterRefill() {
    long[] now = {0L};
    TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(2, 1.0), () -> now[0]);

    assertThat(limiter.tryAcquire("client").allowed()).isTrue();
    assertThat(limiter.tryAcquire("client").allowed()).isTrue();

    TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("client");
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

    now[0] = 1_000; // one second later a token has refilled
    assertThat(limiter.tryAcquire("client").allowed()).isTrue();
  }

  @Test
  void keysAreLimitedIndependently() {
    long[] now = {0L};
    TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(1, 0.0), () -> now[0]);

    assertThat(limiter.tryAcquire("client-a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("client-a").allowed()).isFalse();
    // a different client is unaffected by the first client's budget
    assertThat(limiter.tryAcquire("client-b").allowed()).isTrue();
  }

  @Test
  void reclaimsBucketsThatHaveRefilledToCapacity() {
    long[] now = {0L};
    // maxBuckets 2 so the third distinct key forces the sweep that the ceiling exists to trigger.
    TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(1, 1.0, 2), () -> now[0]);

    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    assertThat(limiter.trackedBuckets()).isEqualTo(2);

    // Both buckets are back at capacity, so they carry no state a fresh bucket would not.
    now[0] = 5_000;
    assertThat(limiter.tryAcquire("c").allowed()).isTrue();

    assertThat(limiter.trackedBuckets())
        .as("full buckets must be reclaimed rather than retained for the life of the process")
        .isEqualTo(1);
  }

  @Test
  void doesNotEvictBucketsThatAreStillRestricting() {
    long[] now = {0L};
    TokenBucketRateLimiter limiter =
        new TokenBucketRateLimiter(properties(10, 1.0, 2), () -> now[0]);

    // "a" is drained to nothing; "b" spends a single token. One second of refill at 1/s returns
    // "b" to capacity but leaves "a" at 1, so the two are on opposite sides of the sweep predicate
    // at the same instant -- which is the only moment the distinction can be observed.
    for (int index = 0; index < 10; index++) {
      assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }
    assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    assertThat(limiter.tryAcquire("b").allowed()).isTrue();

    now[0] = 1_000;
    assertThat(limiter.tryAcquire("c").allowed()).isTrue();

    assertThat(limiter.trackedBuckets())
        .as("the full bucket is reclaimed and the drained one is kept, so the count holds at two")
        .isEqualTo(2);

    // The proof that "a" survived rather than being evicted and recreated: it has the one token it
    // refilled, not the ten a fresh bucket would start with.
    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("a").allowed())
        .as("a drained bucket must keep its state through a sweep, or eviction is a limit bypass")
        .isFalse();
  }

  @Test
  void shedsUnknownKeysAtTheCeilingAndKeepsServingEstablishedOnes() {
    long[] now = {0L};
    // No refill, so nothing is ever reclaimable and the ceiling is genuinely reached.
    TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(4, 0.0, 2), () -> now[0]);

    assertThat(limiter.tryAcquire("established-a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("established-b").allowed()).isTrue();
    assertThat(limiter.trackedBuckets()).isEqualTo(2);

    TokenBucketRateLimiter.Decision shed = limiter.tryAcquire("newcomer");
    assertThat(shed.allowed())
        .as("an unknown key must be shed rather than grown into, once the table is full")
        .isFalse();
    assertThat(shed.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

    assertThat(limiter.trackedBuckets())
        .as("shedding must not add the key it refused")
        .isEqualTo(2);

    // The point of shedding rather than growing: clients already known keep their service.
    assertThat(limiter.tryAcquire("established-a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("established-b").allowed()).isTrue();
  }

  @Test
  void bucketTableStaysBoundedUnderKeyRotation() {
    long[] now = {0L};
    TokenBucketRateLimiter limiter =
        new TokenBucketRateLimiter(properties(10, 100.0, 64), () -> now[0]);

    // The attack the bound exists for: every request arrives under an address never seen before.
    for (int index = 0; index < 20_000; index++) {
      now[0] += 300; // past MIN_SWEEP_INTERVAL_MILLIS, so sweeps are not throttled away
      limiter.tryAcquire("2001:db8::" + index);
    }

    assertThat(limiter.trackedBuckets())
        .as("20,000 distinct keys must not leave 20,000 live buckets behind")
        .isLessThanOrEqualTo(64);
  }
}
