package io.ramals.learningplatform.grounding;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The precision at which an {@link Instant} survives the authoritative store.
 *
 * <p>PostgreSQL {@code timestamptz} has {@code datetime_precision = 6}: it keeps microseconds and
 * discards everything finer. {@code Clock.systemUTC()} on Linux produces nanoseconds. Any value
 * that is both hashed into a durable identity <em>and</em> written to a column therefore has two
 * different forms -- the one that was hashed, and the one that can ever be read back -- and an
 * identity derived from the first can never be reconstructed from the second.
 *
 * <p>That is not a rounding nicety. It made a commissioned diagnostic request unrecoverable: the
 * replacement worker re-derived the grounded context at the persisted {@code asOf}, produced a
 * different {@code contextId}, and the commission-identity guard correctly rejected it on every
 * retry until the step exhausted its attempts.
 *
 * <p>Canonicalize once, at the boundary that mints or reconstructs a durable identity, so the value
 * that is hashed is by construction the value the database can return. The operation is idempotent:
 * canonicalizing a value that has already been through PostgreSQL is a no-op, which is what lets the
 * original and the reconstruction agree exactly.
 *
 * <p>This deliberately does not relax any equality check. Identity stays exact; it is the input that
 * becomes well-defined.
 */
public final class DurableInstant {

  /** Matches PostgreSQL {@code timestamptz} precision. */
  public static final ChronoUnit PRECISION = ChronoUnit.MICROS;

  private DurableInstant() {}

  /**
   * Returns {@code moment} at the precision the authoritative store preserves.
   *
   * @param moment the instant to canonicalize; {@code null} is returned unchanged so callers can
   *     keep their own nullability contracts
   */
  public static Instant canonical(Instant moment) {
    return moment == null ? null : moment.truncatedTo(PRECISION);
  }
}
