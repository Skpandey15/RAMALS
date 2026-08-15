package io.ramals.learningplatform.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-key token-bucket rate limiter. Each key (a client identity) refills continuously at a fixed
 * rate up to a capacity; a request consumes one token. Deterministic given its clock, so recovery is
 * testable. In-memory and per-instance, which is sufficient for MVP-0 abuse mitigation.
 */
@Component
public class TokenBucketRateLimiter {

  private final RateLimitProperties properties;
  private final LongSupplier clockMillis;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  @Autowired
  public TokenBucketRateLimiter(RateLimitProperties properties) {
    this(properties, System::currentTimeMillis);
  }

  TokenBucketRateLimiter(RateLimitProperties properties, LongSupplier clockMillis) {
    this.properties = properties;
    this.clockMillis = clockMillis;
  }

  public Decision tryAcquire(String key) {
    Bucket bucket = buckets.computeIfAbsent(
        key, ignored -> new Bucket(properties.getCapacity(), clockMillis.getAsLong()));
    synchronized (bucket) {
      long now = clockMillis.getAsLong();
      bucket.refill(now, properties);
      if (bucket.tokens >= 1.0) {
        bucket.tokens -= 1.0;
        return new Decision(true, 0);
      }
      return new Decision(false, retryAfterSeconds(bucket));
    }
  }

  private long retryAfterSeconds(Bucket bucket) {
    double refill = properties.getRefillPerSecond();
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

    private void refill(long now, RateLimitProperties properties) {
      double elapsedSeconds = Math.max(0, now - lastRefillMillis) / 1000.0;
      tokens = Math.min(properties.getCapacity(), tokens + elapsedSeconds * properties.getRefillPerSecond());
      lastRefillMillis = now;
    }
  }
}
