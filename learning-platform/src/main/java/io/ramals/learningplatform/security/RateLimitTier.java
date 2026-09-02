package io.ramals.learningplatform.security;

/**
 * One token-bucket tier: how large a burst may be, how fast the allowance refills, and how many
 * distinct keys may be tracked at once.
 *
 * <p>MVP-0 runs two tiers with different jobs. The pre-authentication tier is keyed on client IP and
 * exists to shed floods before any JWT is validated. The post-authentication tier is keyed on the
 * token subject and is the per-user fair-use limit.
 */
public interface RateLimitTier {

  int getCapacity();

  double getRefillPerSecond();

  /**
   * Ceiling on simultaneously retained buckets.
   *
   * <p>Belongs to the tier rather than to the limiter because the two tiers bound different
   * populations: the IP tier's key space is the reachable internet, while the subject tier's is the
   * set of real authenticated learners. Sizing them from one number would either starve the first or
   * leave the second able to hold far more state than it can legitimately need.
   *
   * <p>See {@link TokenBucketRateLimiter} for what happens on reaching it — full buckets are
   * reclaimed first, and only then are unknown keys shed.
   */
  int getMaxBuckets();
}
