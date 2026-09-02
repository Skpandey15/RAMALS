package io.ramals.learningplatform.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-key token-bucket rate limiter. Each key (a client identity) refills continuously at a fixed
 * rate up to a capacity; a request consumes one token. Deterministic given its clock, so recovery is
 * testable. In-memory and per-instance, which is sufficient for MVP-0 abuse mitigation.
 *
 * <p>One instance serves one {@link RateLimitTier}; the application runs a pre-authentication tier
 * keyed on client IP and a post-authentication tier keyed on the token subject.
 *
 * <h2>Why the key map is bounded</h2>
 *
 * <p>The map was previously unbounded: every distinct key ever seen kept a bucket for the lifetime
 * of the process, and nothing removed it. {@code ClientAddressResolver} closed the spoofing half of
 * that problem — a key is now an address the peer actually owns rather than one it can write into a
 * header — but owning addresses is cheap. A caller with a routed IPv6 /64 has 2^64 of them, so
 * unbounded retention converts address rotation into steady heap growth and, eventually, an
 * out-of-memory kill. That is a worse outcome than any request the limiter was defending against.
 *
 * <p>The bound rests on one observation: <strong>a bucket refilled to capacity is indistinguishable
 * from a bucket that does not exist.</strong> Both admit the next request and both start it with a
 * full allowance. So evicting full buckets costs no enforcement at all — it is not a trade of
 * accuracy for memory, it is the removal of entries that were carrying no information. Under the IP
 * tier's default refill of 300 tokens/second a bucket returns to full 3.3ms after a single request,
 * so a sweep reclaims nearly everything and the residents are the keys genuinely mid-consumption.
 *
 * <p>{@link RateLimitTier#getMaxBuckets()} is the backstop beneath that, for the case where arrivals
 * outrun sweeping. On reaching it the limiter sheds: keys that already hold a bucket keep being
 * served normally, and keys that do not are refused until a sweep makes room. Refusing unknown keys
 * rather than admitting them is deliberate. The alternative to a bounded map is not a more generous
 * service, it is the heap filling and every client losing the service at once; shedding new arrivals
 * keeps established clients working through exactly the flood that would otherwise end them.
 *
 * <h2>Why admission is serialised and the rest is not</h2>
 *
 * <p>The ceiling was first written as a size check followed by {@code computeIfAbsent}. Those are two
 * operations, so under concurrency they do not compose: N threads arriving with N unseen keys can
 * each read {@code size() == maxBuckets - 1}, each conclude there is room, and each then insert. The
 * table ends up at {@code maxBuckets - 1 + N}. The bound held in every sequential test and was not a
 * bound at all — and a key-rotation flood, the thing it exists to survive, is precisely the workload
 * that arrives concurrently with unseen keys.
 *
 * <p>So every mutation of the map — the size check, the sweep and the insert — happens under
 * {@link #admissionLock}, which makes {@code trackedBuckets() <= maxBuckets} an invariant rather
 * than a likelihood. Traffic from a key that already has a bucket never touches that lock: it is
 * served by a {@code get} on a {@link ConcurrentHashMap} and then synchronises on its own bucket, so
 * established clients are unaffected by contention among newcomers. Serialising first-seen keys is
 * acceptable precisely because a flood of them is the abuse case; the work under the lock is a map
 * get, a size read and a put, with the O(n) sweep throttled to at most one per
 * {@link #MIN_SWEEP_INTERVAL_MILLIS}.
 *
 * <p>Lock ordering is one-way and therefore deadlock-free: the sweeper takes {@code admissionLock}
 * and then individual bucket monitors, while {@code tryAcquire} takes a bucket monitor while holding
 * nothing. No path takes them in the opposite order.
 */
public class TokenBucketRateLimiter {

  private static final Logger LOGGER = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

  /**
   * Floor between full sweeps.
   *
   * <p>A sweep walks every entry, so an unthrottled one would let a flood at the ceiling turn each
   * arriving request into a full scan — the limiter becoming the amplifier. At the ceiling the cost
   * is therefore bounded to one scan per interval regardless of arrival rate.
   */
  private static final long MIN_SWEEP_INTERVAL_MILLIS = 250L;

  /**
   * {@code Retry-After} for a shed request.
   *
   * <p>Short because the condition is transient by construction: the next sweep is at most
   * {@link #MIN_SWEEP_INTERVAL_MILLIS} away and full buckets are reclaimed continuously. Telling a
   * caller to wait minutes would outlast the pressure that caused the refusal.
   */
  private static final long SHED_RETRY_AFTER_SECONDS = 1L;

  private final RateLimitTier tier;
  private final LongSupplier clockMillis;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /**
   * Guards every mutation of {@link #buckets}, and nothing else.
   *
   * <p>A dedicated object rather than {@code this}: the monitor is an implementation detail of
   * admission, and a caller holding a reference to the limiter must not be able to join or stall it.
   */
  private final Object admissionLock = new Object();

  /** Guarded by {@link #admissionLock}. Plain fields: the lock already orders every access. */
  private long nextSweepMillis = Long.MIN_VALUE;
  private boolean shedding;

  public TokenBucketRateLimiter(RateLimitTier tier) {
    this(tier, System::currentTimeMillis);
  }

  TokenBucketRateLimiter(RateLimitTier tier, LongSupplier clockMillis) {
    this.tier = tier;
    this.clockMillis = clockMillis;
  }

  public Decision tryAcquire(String key) {
    long now = clockMillis.getAsLong();
    Bucket bucket = buckets.get(key);
    if (bucket == null) {
      bucket = admit(key, now);
      if (bucket == null) {
        return new Decision(false, SHED_RETRY_AFTER_SECONDS);
      }
    }
    synchronized (bucket) {
      bucket.refill(now, tier);
      if (bucket.tokens >= 1.0) {
        bucket.tokens -= 1.0;
        return new Decision(true, 0);
      }
      return new Decision(false, retryAfterSeconds(bucket));
    }
  }

  /**
   * Returns a bucket for a key that has none, or null when the map is full and must not grow.
   *
   * <p>Sweeping is attempted before refusing, so the ceiling is only reported after the cheap
   * reclamation has been given its chance on this very request.
   */
  private Bucket admit(String key, long now) {
    synchronized (admissionLock) {
      // Re-read inside the lock. Between the caller's miss and this point another thread may have
      // admitted the same key; returning its bucket rather than a second one is what keeps a key's
      // allowance single-valued.
      Bucket existing = buckets.get(key);
      if (existing != null) {
        return existing;
      }
      int maxBuckets = tier.getMaxBuckets();
      if (buckets.size() >= maxBuckets) {
        sweepFullBuckets(now);
        if (buckets.size() >= maxBuckets) {
          if (!shedding) {
            shedding = true;
            LOGGER.warn(
                "Rate-limit bucket table reached its {} entry ceiling; requests from keys without "
                    + "an existing bucket are being shed until a sweep reclaims capacity",
                maxBuckets);
          }
          return null;
        }
      }
      if (shedding) {
        shedding = false;
        LOGGER.info("Rate-limit bucket table recovered below its ceiling; no longer shedding new keys");
      }
      // put, not computeIfAbsent: the absence was established above under this same lock, so the
      // atomicity computeIfAbsent provides is already held and would only obscure that.
      Bucket created = new Bucket(tier.getCapacity(), now);
      buckets.put(key, created);
      return created;
    }
  }

  /**
   * Removes every bucket that has refilled to capacity.
   *
   * <p>Called only from {@link #admit}, so {@link #admissionLock} is already held: the lock is the
   * single-sweeper guarantee, and the explicit CAS this used to carry was redundant beside it.
   * Interval throttling remains, and matters more now — a sweep walks every entry, so at the ceiling
   * it must not run per arriving request.
   *
   * <p>A bucket can be removed while another thread holds a reference to it and is about to consume
   * from it. That thread's decrement then applies to a detached bucket and is lost, so the key gets
   * one extra token before its next bucket is created. The imprecision is bounded by one token per
   * sweep per key and can only touch keys that were at full capacity — that is, keys the limiter was
   * not restricting. Holding a global lock to close it would cost far more than it protects.
   */
  private void sweepFullBuckets(long now) {
    if (now < nextSweepMillis) {
      return;
    }
    try {
      int capacity = tier.getCapacity();
      buckets.values().removeIf(candidate -> {
        synchronized (candidate) {
          candidate.refill(now, tier);
          return candidate.tokens >= capacity;
        }
      });
    } finally {
      nextSweepMillis = now + MIN_SWEEP_INTERVAL_MILLIS;
    }
  }

  /** Live bucket count. Exposed for tests and for the operational assertions that bound this map. */
  int trackedBuckets() {
    return buckets.size();
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
