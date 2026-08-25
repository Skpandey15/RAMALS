package io.ramals.learningplatform.qualification;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.qualification.QualificationFault.ClaimBarrierPaths;
import io.ramals.learningplatform.qualification.QualificationFault.ClaimBoundary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
