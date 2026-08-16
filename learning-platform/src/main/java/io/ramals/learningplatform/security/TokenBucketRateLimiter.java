package io.ramals.learningplatform.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key token-bucket rate limiter. Each key (a client identity) refills continuously at a fixed
 * rate up to a capacity; a request consumes one token. Deterministic given its clock, so recovery is
 * testable. In-memory and per-instance, which is sufficient for MVP-0 abuse mitigation.
 *
 * <p>One instance serves one {@link RateLimitTier}; the application runs a pre-authentication tier
 * keyed on client IP and a post-authentication tier keyed on the token subject.
 */
public class TokenBucketRateLimiter {

  private final RateLimitTier tier;
  private final LongSupplier clockMillis;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  public TokenBucketRateLimiter(RateLimitTier tier) {
    this(tier, System::currentTimeMillis);
  }

  TokenBucketRateLimiter(RateLimitTier tier, LongSupplier clockMillis) {
    this.tier = tier;
    this.clockMillis = clockMillis;
  }

  public Decision tryAcquire(String key) {
    Bucket bucket = buckets.computeIfAbsent(
        key, ignored -> new Bucket(tier.getCapacity(), clockMillis.getAsLong()));
    synchronized (bucket) {
      long now = clockMillis.getAsLong();
      bucket.refill(now, tier);
      if (bucket.tokens >= 1.0) {
        bucket.tokens -= 1.0;
        return new Decision(true, 0);
      }
      return new Decision(false, retryAfterSeconds(bucket));
    }
  }

  private long retryAfterSeconds(Bucket bucket) {
    double refill = tier.getRefillPerSecond();
    if (refill <= 0) {
      return 60;
    }
    return Math.max(1, (long) Math.ceil((1.0 - bucket.tokens) / refill));
  }

  /** A rate-limit decision: whether the request is allowed and, if not, when to retry. */
  public record Decision(boolean allowed, long retryAfterSeconds) {
  }

  private static final class Bucket {
    private double tokens;
    private long lastRefillMillis;

    private Bucket(int capacity, long nowMillis) {
      this.tokens = capacity;
      this.lastRefillMillis = nowMillis;
    }

    private void refill(long now, RateLimitTier tier) {
      double elapsedSeconds = Math.max(0, now - lastRefillMillis) / 1000.0;
      tokens = Math.min(tier.getCapacity(), tokens + elapsedSeconds * tier.getRefillPerSecond());
      lastRefillMillis = now;
    }
  }
}
