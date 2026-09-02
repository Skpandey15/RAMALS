package io.ramals.learningplatform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token-bucket rate-limit configuration.
 *
 * <p>The top-level capacity/refill are the <em>pre-authentication</em> tier, keyed on client IP.
 * Its job is to shed floods before any JWT is validated, so it is deliberately generous: a whole
 * office or school behind one NAT must not trip it during normal use. Per-user fairness is the
 * {@link Subject} tier's job, applied after the token is validated.
 */
@ConfigurationProperties(prefix = "ramals.security.rate-limit")
public class RateLimitProperties implements RateLimitTier {

  private boolean enabled = true;
  private int capacity = 600;
  private double refillPerSecond = 300.0;
  // The reachable internet is this tier's key space, so the ceiling is sized to be unreachable in
  // normal operation and cheap if reached: ~100k entries is single-digit megabytes, and a bucket
  // refills fully 3.3ms after one request, so residency is measured in milliseconds.
  private int maxBuckets = 100_000;
  private final Subject subject = new Subject();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public int getCapacity() {
    return capacity;
  }

  public void setCapacity(int capacity) {
    this.capacity = capacity;
  }

  @Override
  public double getRefillPerSecond() {
    return refillPerSecond;
  }

  public void setRefillPerSecond(double refillPerSecond) {
    this.refillPerSecond = refillPerSecond;
  }

  @Override
  public int getMaxBuckets() {
    return maxBuckets;
  }

  public void setMaxBuckets(int maxBuckets) {
    this.maxBuckets = maxBuckets;
  }

  public Subject getSubject() {
    return subject;
  }

  /**
   * Per-learner fair-use tier, keyed on the authenticated token subject.
   *
   * <p>These are the limits an individual user actually experiences. They carry the values the
   * IP tier used to hold, because that is what the original limit was trying to express.
   */
  public static class Subject implements RateLimitTier {

    private int capacity = 120;
    private double refillPerSecond = 60.0;
    // Bounded by the learners authenticated against one instance, not by the internet, so it sits
    // well below the IP tier: minting a subject key costs a validated JWT.
    private int maxBuckets = 50_000;

    @Override
    public int getCapacity() {
      return capacity;
    }

    public void setCapacity(int capacity) {
      this.capacity = capacity;
    }

    @Override
    public double getRefillPerSecond() {
      return refillPerSecond;
    }

    public void setRefillPerSecond(double refillPerSecond) {
      this.refillPerSecond = refillPerSecond;
    }

    @Override
    public int getMaxBuckets() {
      return maxBuckets;
    }

    public void setMaxBuckets(int maxBuckets) {
      this.maxBuckets = maxBuckets;
    }
  }
}
