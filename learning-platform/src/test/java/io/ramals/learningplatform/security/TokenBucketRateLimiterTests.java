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
}
