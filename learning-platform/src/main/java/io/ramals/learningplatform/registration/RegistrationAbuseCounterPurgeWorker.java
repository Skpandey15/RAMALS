package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.UuidV7;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes abuse-counter rows whose window has long closed.
 *
 * <p><strong>Why this exists at all.</strong> {@code identity.abuse_counter} gains a row per
 * dimension per window and never loses one. Registration alone contributes a source bucket and an
 * email bucket per attempt; mobile verification contributes three more. Left alone the table grows
 * without bound, on the write path of a public unauthenticated endpoint, until the insert that
 * enforces the rate limit is itself the slowest thing in the request. A rate limiter that degrades
 * the service it protects has inverted its purpose — so the sweep is part of the control, not
 * housekeeping alongside it.
 *
 * <p><strong>Why a fixed cutoff rather than a per-window one.</strong> A row is dead the moment its
 * window closes, and every window here is at most an hour. Retaining a week is far longer than any
 * ceiling needs and short enough to bound the table; the margin exists so that a counter is still
 * available to an operator reconstructing an abuse episode a few days later.
 *
 * <p><strong>Why it holds no state.</strong> The cutoff is computed from the clock on every run, so a
 * sweep that never happened is indistinguishable from one that deleted nothing, and a process that
 * dies mid-sweep leaves a committed partial delete the next run simply continues. There is no cursor
 * to lose. The same property is what makes it safe to run on every replica: concurrent sweeps delete
 * disjoint or already-deleted rows.
 *
 * <p><strong>Why failure is swallowed here.</strong> A scheduled method that throws is not retried by
 * the default scheduler, and killing the only thing that bounds the table is a worse outcome than a
 * skipped sweep. The failure is logged at ERROR, which is the alerting signal.
 *
 * <p>Enabled by default, unlike the Contract B purge worker it is modelled on. That worker deletes
 * model output and is therefore something an operator should switch on deliberately; this one deletes
 * expired counters, which nothing reads, and defaulting it off would mean every deployment silently
 * accrues the growth this exists to prevent.
 */
@Component
@ConditionalOnProperty(prefix = "ramals.registration", name = "abuse-counter-purge.enabled",
    havingValue = "true", matchIfMissing = true)
class RegistrationAbuseCounterPurgeWorker {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(RegistrationAbuseCounterPurgeWorker.class);

  private final RegistrationRepository registrations;
  private final RegistrationProperties properties;

  RegistrationAbuseCounterPurgeWorker(RegistrationRepository registrations,
      RegistrationProperties properties) {
    this.registrations = registrations;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${ramals.registration.abuse-counter-purge.interval-ms:3600000}")
  public void sweep() {
    // A scheduler thread carries no MDC. Correlating each run keeps its log lines joinable to one
    // another, which is the difference between a readable sweep and three orphaned lines.
    try (var ignored = CorrelationContext.withCorrelation(UuidV7.generate().toString(), null)) {
      Instant cutoff = Instant.now()
          .minus(Duration.ofDays(properties.getSms().getAbuseCounterRetentionDays()));
      try {
        int deleted = registrations.purgeAbuseCountersBefore(cutoff);
        BusinessEventLogger.info(LOGGER, "registration.abuse-counter.purged",
            "Expired registration abuse counters removed",
            Map.of("deletedRows", deleted,
                "retentionDays", properties.getSms().getAbuseCounterRetentionDays(),
                "outcome", "SUCCESS"));
      } catch (RuntimeException failure) {
        BusinessEventLogger.error(LOGGER, "registration.abuse-counter.purge-failed",
            "Registration abuse counter sweep failed", failure,
            Map.of("retentionDays", properties.getSms().getAbuseCounterRetentionDays(),
                "outcome", "FAILURE"));
      }
    }
  }
}
