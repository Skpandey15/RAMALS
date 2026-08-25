package io.ramals.learningplatform.qualification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qualification-only workflow boundary controls.
 *
 * <p>These controls are deliberately environment-driven and disabled unless the isolated T15
 * operator enables them. They are not a retry mechanism, a production feature flag, or a business
 * decision: they only hold a real worker at a named boundary for an explicitly controlled
 * qualification perturbation.
 */
public final class QualificationFault {

  public static final String ENABLED = "RAMALS_QUALIFICATION_FAULT_ENABLED";
  public static final String WINDOW = "RAMALS_QUALIFICATION_FAULT_WINDOW";
  public static final String RUN_ID = "RAMALS_QUALIFICATION_FAULT_RUN_ID";
  public static final String REQUEST_ID = "RAMALS_QUALIFICATION_FAULT_REQUEST_ID";
  public static final String PAUSE_MS = "RAMALS_QUALIFICATION_FAULT_PAUSE_MS";
  public static final String STEP = "RAMALS_QUALIFICATION_FAULT_STEP";
  public static final String CLAIM_BARRIER_DIRECTORY =
      "RAMALS_QUALIFICATION_FAULT_CLAIM_BARRIER_DIRECTORY";
  public static final String POD_NAME = "RAMALS_QUALIFICATION_POD_NAME";
  public static final String POD_UID = "RAMALS_QUALIFICATION_POD_UID";
  public static final String POD_IP = "RAMALS_QUALIFICATION_POD_IP";

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
    if (!armed(window, runId, requestId, null)) {
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

  /**
   * Holds one exact, already-committed workflow claim until its qualification release file exists.
   *
   * <p>The barrier key contains the run, step, attempt and execution token. Two claims for the same
   * run and step therefore have independent marker and release files. The worker resumes from this
   * call into the normal production effect and completion path; the barrier never performs or
   * bypasses persistence itself.
   */
  public static void pauseAfterClaim(
      String runId,
      String step,
      int attemptCount,
      String executionToken,
      String interactionId,
      String traceId) {
    if (!armed(Window.WORKFLOW_AFTER_CLAIM, runId, null, step)) {
      return;
    }
    String configuredDirectory = System.getenv(CLAIM_BARRIER_DIRECTORY);
    if (configuredDirectory == null || configuredDirectory.isBlank()) {
      // Preserve the existing timed crash boundary for the after-claim pod-death scenario. The
      // stale-worker qualification explicitly configures a release directory and never uses this
      // timed mode as its correctness control.
      pause(Window.WORKFLOW_AFTER_CLAIM, runId, null);
      return;
    }

    ClaimBoundary boundary =
        new ClaimBoundary(
            runId,
            step,
            attemptCount,
            executionToken,
            value(System.getenv(POD_NAME)),
            value(System.getenv(POD_UID)),
            value(System.getenv(POD_IP)),
            value(interactionId),
            value(traceId),
            ProcessHandle.current().pid(),
            Thread.currentThread().threadId(),
            Instant.now().toString());
    awaitExplicitClaimRelease(Path.of(configuredDirectory), boundary);
  }

  static void awaitExplicitClaimRelease(Path directory, ClaimBoundary boundary) {
    ClaimBarrierPaths paths = claimBarrierPaths(directory, boundary);
    boolean interrupted = false;
    try {
      Files.createDirectories(directory);
      Files.deleteIfExists(paths.release());
      try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
        directory.register(
            watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        writeAtomically(paths.marker(), markerJson(boundary, paths));
        LOGGER
            .atWarn()
            .addKeyValue("operation", "qualification.claim.barrier")
            .addKeyValue("window", Window.WORKFLOW_AFTER_CLAIM.name())
            .addKeyValue("runId", boundary.runId())
            .addKeyValue("step", boundary.step())
            .addKeyValue("attempt", boundary.attemptCount())
            .addKeyValue("executionToken", boundary.executionToken())
            .addKeyValue("podName", boundary.podName())
            .addKeyValue("podUid", boundary.podUid())
            .addKeyValue("podIp", boundary.podIp())
            .addKeyValue("pid", boundary.processId())
            .addKeyValue("threadId", boundary.threadId())
            .addKeyValue("releaseFile", paths.release().toString())
            .log("qualification claim barrier reached; explicit release required");

        while (!Files.isRegularFile(paths.release())) {
          try {
            WatchKey signal = watcher.take();
            signal.pollEvents();
            if (!signal.reset()) {
              throw new IllegalStateException(
                  "qualification claim barrier directory is no longer watchable");
            }
          } catch (InterruptedException signal) {
            // A scheduler or lifecycle interrupt must not release a stale-worker claimant. Defer
            // restoration of the interrupt flag until the exact release file has been observed.
            interrupted = true;
          }
        }
      }
      LOGGER
          .atWarn()
          .addKeyValue("operation", "qualification.claim.barrier")
          .addKeyValue("outcome", "RELEASED")
          .addKeyValue("window", Window.WORKFLOW_AFTER_CLAIM.name())
          .addKeyValue("runId", boundary.runId())
          .addKeyValue("step", boundary.step())
          .addKeyValue("attempt", boundary.attemptCount())
          .addKeyValue("executionToken", boundary.executionToken())
          .addKeyValue("podName", boundary.podName())
          .log("qualification claim barrier released; resuming production path");
    } catch (IOException failure) {
      throw new IllegalStateException("qualification claim barrier failed", failure);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static ClaimBarrierPaths claimBarrierPaths(Path directory, ClaimBoundary boundary) {
    String key =
        safeKeyPart(boundary.runId())
            + "__"
            + safeKeyPart(boundary.step())
            + "__"
            + boundary.attemptCount()
            + "__"
            + safeKeyPart(boundary.executionToken());
    return new ClaimBarrierPaths(
        directory.resolve("held-" + key + ".json"), directory.resolve("release-" + key));
  }

  private static boolean armed(Window window, String runId, String requestId, String step) {
    return Boolean.parseBoolean(System.getenv(ENABLED))
        && window.name().equals(System.getenv(WINDOW))
        && matches(System.getenv(RUN_ID), runId)
        && matches(System.getenv(REQUEST_ID), requestId)
        && matches(System.getenv(STEP), step);
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path temporary = Files.createTempFile(target.getParent(), ".claim-boundary-", ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException atomicMoveUnavailable) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String markerJson(ClaimBoundary boundary, ClaimBarrierPaths paths) {
    return """
        {
          "schema":"m2-t15.workflow-after-claim-barrier.v1",
          "state":"HELD",
          "window":"WORKFLOW_AFTER_CLAIM",
          "runId":"%s",
          "step":"%s",
          "attemptCount":%d,
          "executionToken":"%s",
          "podName":"%s",
          "podUid":"%s",
          "podIp":"%s",
          "processId":%d,
          "threadId":%d,
          "interactionId":"%s",
          "traceId":"%s",
          "heldAtUtc":"%s",
          "markerPath":"%s",
          "releasePath":"%s"
        }
        """
        .formatted(
            json(boundary.runId()),
            json(boundary.step()),
            boundary.attemptCount(),
            json(boundary.executionToken()),
            json(boundary.podName()),
            json(boundary.podUid()),
            json(boundary.podIp()),
            boundary.processId(),
            boundary.threadId(),
            json(boundary.interactionId()),
            json(boundary.traceId()),
            json(boundary.heldAtUtc()),
            json(paths.marker().toString()),
            json(paths.release().toString()));
  }

  private static String safeKeyPart(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("unsafe qualification claim barrier key");
    }
    return value;
  }

  private static String json(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  record ClaimBoundary(
      String runId,
      String step,
      int attemptCount,
      String executionToken,
      String podName,
      String podUid,
      String podIp,
      String interactionId,
      String traceId,
      long processId,
      long threadId,
      String heldAtUtc) {}

  record ClaimBarrierPaths(Path marker, Path release) {}

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
