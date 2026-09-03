package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.UUID;

/**
 * A candidate for a diagnostic form: one verified item of the pinned assessment version, together
 * with when this learner last saw it.
 *
 * <p>{@code lastPresentedAt} is null when the item is outside the recency window the selector was
 * given -- either never presented to this learner, or presented longer ago than the window. The
 * selector therefore reads it as "recently seen", not "ever seen", and the window is the query's
 * responsibility rather than the algorithm's.
 *
 * <p>Carries no stem, options, or answer key. Selection decides <em>which</em> items are asked and
 * needs only their skill, difficulty, and history to do it.
 */
public record EligibleItem(
    UUID itemVersionId,
    String skillCode,
    String difficulty,
    Instant lastPresentedAt) {

  boolean recentlyPresented() {
    return lastPresentedAt != null;
  }
}
