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
 * <p>{@code identity.abuse_counter} gains a row per dimension per window and never loses one, on
 * the write path of a public endpoint, until the insert that enforces the rate limit is the slowest
 * thing in the request. A limiter that degrades the service it protects has inverted its purpose,
 * so the sweep is part of the control rather than housekeeping beside it.
 *
 * <p>Holds no state: the cutoff is computed from the clock each run, so a missed sweep and an empty
 * one are indistinguishable and concurrent replicas delete disjoint or already-deleted rows.
 * Failure is logged and swallowed, because killing the only thing that bounds the table is worse
 * than a skipped sweep.
 *
 * <p>Enabled by default, unlike the Contract B purge worker it is modelled on: that one deletes
 * model output and should be switched on deliberately, this one deletes expired counters nothing
 * reads.
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
