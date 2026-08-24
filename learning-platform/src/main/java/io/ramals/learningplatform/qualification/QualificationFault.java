package io.ramals.learningplatform.qualification;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qualification-only crash boundary controls.
 *
 * <p>These controls are deliberately environment-driven and disabled unless the isolated T15
 * operator enables them. They are not a retry mechanism, a production feature flag, or a business
 * decision: they only hold a real worker at a named boundary long enough for the qualification
 * runner to delete its pod.
 */
public final class QualificationFault {

  public static final String ENABLED = "RAMALS_QUALIFICATION_FAULT_ENABLED";
  public static final String WINDOW = "RAMALS_QUALIFICATION_FAULT_WINDOW";
  public static final String RUN_ID = "RAMALS_QUALIFICATION_FAULT_RUN_ID";
  public static final String REQUEST_ID = "RAMALS_QUALIFICATION_FAULT_REQUEST_ID";
  public static final String PAUSE_MS = "RAMALS_QUALIFICATION_FAULT_PAUSE_MS";

  private static final Logger LOGGER = LoggerFactory.getLogger(QualificationFault.class);
  private static final long DEFAULT_PAUSE_MS = 120_000L;
  private static final long MAX_PAUSE_MS = 600_000L;

  private QualificationFault() {}

  public enum Window {
    WORKFLOW_AFTER_CLAIM,
    WORKFLOW_AFTER_EVIDENCE_EFFECT,
    WORKFLOW_AFTER_MASTERY_EFFECT,
    WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION,
    WORKFLOW_AFTER_DIAGNOSTIC_OUTCOME_COMMIT,
    WORKFLOW_AFTER_ADAPTATION_HANDOFF,
    ADAPTATION_AFTER_COMMISSION
  }

  /** Holds the current process at {@code window} when the operator has armed that exact boundary. */
  public static void pause(Window window, String runId, String requestId) {
    if (!Boolean.parseBoolean(System.getenv(ENABLED))) {
      return;
    }
    if (!window.name().equals(System.getenv(WINDOW))) {
      return;
    }
    if (!matches(System.getenv(RUN_ID), runId)
        || !matches(System.getenv(REQUEST_ID), requestId)) {
      return;
    }

    long pauseMillis = boundedPause(System.getenv(PAUSE_MS));
    LOGGER
        .atWarn()
        .addKeyValue("operation", "qualification.fault")
        .addKeyValue("window", window.name())
        .addKeyValue("runId", runId)
        .addKeyValue("requestId", requestId)
        .addKeyValue("pid", ProcessHandle.current().pid())
        .addKeyValue("pauseMs", pauseMillis)
        .log("qualification crash boundary reached; delete this pod now");
    long deadline = System.nanoTime() + (pauseMillis * 1_000_000L);
    boolean interrupted = false;
    try {
      while (true) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
          break;
        }
        try {
          long remainingMillis = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
          Thread.sleep(remainingMillis);
        } catch (InterruptedException signal) {
          // A scheduler or lifecycle interrupt must not make the boundary disappear before the
          // operator can kill the pod. Restore the flag after the qualification window instead.
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (interrupted) {
      LOGGER
          .atWarn()
          .addKeyValue("operation", "qualification.fault")
          .addKeyValue("window", window.name())
          .addKeyValue("runId", runId)
          .addKeyValue("requestId", requestId)
          .log("qualification crash boundary was interrupted");
    }
  }

  private static boolean matches(String configured, String actual) {
    return configured == null
        || configured.isBlank()
        || (actual != null && configured.equals(actual));
  }

  private static long boundedPause(String configured) {
    if (configured == null || configured.isBlank()) {
      return DEFAULT_PAUSE_MS;
    }
    try {
      long parsed = Long.parseLong(configured.trim());
      return Math.max(1L, Math.min(MAX_PAUSE_MS, parsed));
    } catch (NumberFormatException invalid) {
      return DEFAULT_PAUSE_MS;
    }
  }
}
