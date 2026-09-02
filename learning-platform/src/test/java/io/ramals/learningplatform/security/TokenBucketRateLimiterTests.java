package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
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

  @Test
  @DisplayName("the ceiling holds when hundreds of unseen keys arrive at once")
  void ceilingHoldsUnderConcurrentFirstSeenKeys() throws Exception {
    // The bound was previously a size check followed by computeIfAbsent -- two operations, so N
    // threads could each read size < maxBuckets and each then insert. This is the test the
    // sequential rotation case cannot be: it fails on the pre-fix implementation and passes on the
    // serialised one, because only concurrency distinguishes them.
    int maxBuckets = 64;
    int threads = 64;
    int keysPerThread = 32;
    // Zero refill so nothing is ever reclaimable: the ceiling is the only thing that can bound the
    // table, which is exactly what is under test.
    TokenBucketRateLimiter limiter =
        new TokenBucketRateLimiter(properties(4, 0.0, maxBuckets), () -> 0L);

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    // A barrier, not a start flag: every thread is released in the same instant, which is what
    // makes the interleaving dense enough to expose a check-then-act window.
    CyclicBarrier startTogether = new CyclicBarrier(threads);
    List<Future<?>> runs = new ArrayList<>();
    try {
      for (int thread = 0; thread < threads; thread++) {
        int id = thread;
        runs.add(pool.submit(() -> {
          startTogether.await(20, TimeUnit.SECONDS);
          for (int index = 0; index < keysPerThread; index++) {
            limiter.tryAcquire("2001:db8:" + id + "::" + index);
          }
          return null;
        }));
      }
      for (Future<?> run : runs) {
        run.get(30, TimeUnit.SECONDS); // surfaces an assertion or timeout inside a worker
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(limiter.trackedBuckets())
        .as("%d threads x %d unseen keys must not push the table past its %d ceiling",
            threads, keysPerThread, maxBuckets)
        .isLessThanOrEqualTo(maxBuckets);
  }

  @Test
  @DisplayName("an established key keeps its allowance while newcomers are shed concurrently")
  void establishedKeyIsUnaffectedByConcurrentAdmissionPressure() throws Exception {
    int maxBuckets = 8;
    TokenBucketRateLimiter limiter =
        new TokenBucketRateLimiter(properties(1_000, 0.0, maxBuckets), () -> 0L);

    // Establish the key before the flood, so it owns a bucket the ceiling cannot evict (drained
    // buckets are never full, and only full buckets are reclaimable).
    assertThat(limiter.tryAcquire("established").allowed()).isTrue();

    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CyclicBarrier startTogether = new CyclicBarrier(threads);
    List<Future<?>> runs = new ArrayList<>();
    try {
      for (int thread = 0; thread < threads; thread++) {
        int id = thread;
        runs.add(pool.submit(() -> {
          startTogether.await(20, TimeUnit.SECONDS);
          for (int index = 0; index < 32; index++) {
            limiter.tryAcquire("newcomer-" + id + "-" + index);
          }
          return null;
        }));
      }
      for (Future<?> run : runs) {
        run.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(limiter.trackedBuckets()).isLessThanOrEqualTo(maxBuckets);
    // The point of shedding rather than growing: a client already known keeps being served through
    // the flood. If admission had evicted or replaced its bucket this would fail.
    assertThat(limiter.tryAcquire("established").allowed())
        .as("an established key must keep its service while unknown keys are being shed")
        .isTrue();
  }
}
