package io.ramals.learningplatform.qualification;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.qualification.QualificationFault.ClaimBarrierPaths;
import io.ramals.learningplatform.qualification.QualificationFault.ClaimBoundary;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class QualificationFaultTests {

  @TempDir Path directory;

  @Test
  void explicitClaimBarrierCannotResumeWithoutItsExactReleaseFile() throws Exception {
    ClaimBoundary claimant = claimant(1, "01900000-0000-7000-8000-000000000901", "pod-a");
    ClaimBarrierPaths paths = QualificationFault.claimBarrierPaths(directory, claimant);

    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> held =
          workers.submit(() -> QualificationFault.awaitExplicitClaimRelease(directory, claimant));

      awaitFile(paths.marker());
      assertThat(held.isDone()).isFalse();
      assertThat(Files.readString(paths.marker()))
          .contains("\"state\":\"HELD\"")
          .contains("\"attemptCount\":1")
          .contains("\"executionToken\":\"" + claimant.executionToken() + "\"")
          .contains("\"podUid\":\"uid-pod-a\"");

      Files.createFile(paths.release());
      held.get(2, SECONDS);
    }
  }

  @Test
  void twoClaimsForTheSameRunAndStepHaveIndependentReleases() throws Exception {
    ClaimBoundary claimantA = claimant(1, "01900000-0000-7000-8000-000000000901", "pod-a");
    ClaimBoundary claimantB = claimant(2, "01900000-0000-7000-8000-000000000902", "pod-b");
    ClaimBarrierPaths pathsA = QualificationFault.claimBarrierPaths(directory, claimantA);
    ClaimBarrierPaths pathsB = QualificationFault.claimBarrierPaths(directory, claimantB);

    assertThat(pathsA.marker()).isNotEqualTo(pathsB.marker());
    assertThat(pathsA.release()).isNotEqualTo(pathsB.release());

    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> heldA =
          workers.submit(() -> QualificationFault.awaitExplicitClaimRelease(directory, claimantA));
      Future<?> heldB =
          workers.submit(() -> QualificationFault.awaitExplicitClaimRelease(directory, claimantB));
      awaitFile(pathsA.marker());
      awaitFile(pathsB.marker());

      Files.createFile(pathsA.release());
      heldA.get(2, SECONDS);
      assertThat(heldB.isDone()).isFalse();

      Files.createFile(pathsB.release());
      heldB.get(2, SECONDS);
    }
  }

  @Test
  @SetEnvironmentVariable(key = QualificationFault.ENABLED, value = "true")
  @SetEnvironmentVariable(key = QualificationFault.WINDOW, value = "WORKFLOW_AFTER_CLAIM")
  @SetEnvironmentVariable(key = QualificationFault.RUN_ID, value = "run-qualification-claim")
  @SetEnvironmentVariable(key = QualificationFault.STEP, value = "RECORD_EVALUATION_EVIDENCE")
  @SetEnvironmentVariable(key = QualificationFault.PAUSE_MS, value = "1")
  void pauseAfterClaimFallsBackToTimedBoundaryWhenReleaseDirectoryIsMissing() {
    QualificationFault.pauseAfterClaim(
        "run-qualification-claim",
        "RECORD_EVALUATION_EVIDENCE",
        3,
        "01900000-0000-7000-8000-000000000903",
        "01900000-0000-7000-8000-000000000101",
        "0123456789abcdef0123456789abcdef");
  }

  @Test
  @SetEnvironmentVariable(key = QualificationFault.ENABLED, value = "true")
  @SetEnvironmentVariable(key = QualificationFault.WINDOW, value = "WORKFLOW_AFTER_CLAIM")
  @SetEnvironmentVariable(key = QualificationFault.RUN_ID, value = "run-timed")
  @SetEnvironmentVariable(key = QualificationFault.PAUSE_MS, value = "1")
  void pauseUsesTimedBoundaryWhenClaimBarrierDirectoryIsMissing() {
    QualificationFault.pause(QualificationFault.Window.WORKFLOW_AFTER_CLAIM, "run-timed", null);
  }

  @Test
  void utilityHelpersHandleDefaultsAndUnsafeValues() throws Exception {
    ClaimBoundary boundary = claimant(4, "01900000-0000-7000-8000-000000000904", "pod-d");
    ClaimBarrierPaths paths = QualificationFault.claimBarrierPaths(directory, boundary);

    assertThat(paths.marker().toString())
        .endsWith("held-" + boundary.runId() + "__" + boundary.step() + "__4__" + boundary.executionToken() + ".json");
    assertThat(markerJson(boundary, paths))
        .contains("\"schema\":\"m2-t15.workflow-after-claim-barrier.v1\"")
        .contains("\"state\":\"HELD\"")
        .contains("\"podName\":\"pod-d\"");
    assertThat(invokeMatches(null, "value")).isTrue();
    assertThat(invokeMatches("", "value")).isTrue();
    assertThat(invokeMatches("expected", "expected")).isTrue();
    assertThat(invokeMatches("expected", "other")).isFalse();
    assertThat(invokeBoundedPause("0")).isEqualTo(1L);
    assertThat(invokeBoundedPause("120000")).isEqualTo(120_000L);
    assertThat(invokeBoundedPause("999999999999")).isEqualTo(600_000L);
    assertThat(invokeBoundedPause("nope")).isEqualTo(120_000L);
    assertThat(invokeSafeKeyPart("step.v2-42")).isEqualTo("step.v2-42");
    assertThatThrownBy(() -> invokeSafeKeyPart("bad/key"))
        .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("unsafe qualification claim barrier key");
  }

  private static String markerJson(ClaimBoundary boundary, ClaimBarrierPaths paths) throws Exception {
    Method method = QualificationFault.class.getDeclaredMethod("markerJson", ClaimBoundary.class, ClaimBarrierPaths.class);
    method.setAccessible(true);
    return (String) method.invoke(null, boundary, paths);
  }

  private static boolean invokeMatches(String configured, String actual) throws Exception {
    Method method = QualificationFault.class.getDeclaredMethod("matches", String.class, String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, configured, actual);
  }

  private static long invokeBoundedPause(String configured) throws Exception {
    Method method = QualificationFault.class.getDeclaredMethod("boundedPause", String.class);
    method.setAccessible(true);
    return (long) method.invoke(null, configured);
  }

  private static String invokeSafeKeyPart(String value) throws Exception {
    Method method = QualificationFault.class.getDeclaredMethod("safeKeyPart", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, value);
  }

  private static ClaimBoundary claimant(int attempt, String token, String podName) {
    return new ClaimBoundary(
        "686408d6-26ae-456a-9481-250f49d7570e",
        "RECORD_EVALUATION_EVIDENCE",
        attempt,
        token,
        podName,
        "uid-" + podName,
        "10.0.0." + attempt,
        "01900000-0000-7000-8000-000000000101",
        "0123456789abcdef0123456789abcdef",
        101L + attempt,
        201L + attempt,
        Instant.parse("2026-08-25T04:08:36Z").toString());
  }

  private static void awaitFile(Path path) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
    while (!Files.isRegularFile(path) && Instant.now().isBefore(deadline)) {
      Thread.sleep(10);
    }
    assertThat(path).isRegularFile();
  }
}
