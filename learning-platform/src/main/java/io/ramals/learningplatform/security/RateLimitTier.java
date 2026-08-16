package io.ramals.learningplatform.security;

/**
 * One token-bucket tier: how large a burst may be and how fast the allowance refills.
 *
 * <p>MVP-0 runs two tiers with different jobs. The pre-authentication tier is keyed on client IP and
 * exists to shed floods before any JWT is validated. The post-authentication tier is keyed on the
 * token subject and is the per-user fair-use limit.
 */
public interface RateLimitTier {

  int getCapacity();

  double getRefillPerSecond();
}
