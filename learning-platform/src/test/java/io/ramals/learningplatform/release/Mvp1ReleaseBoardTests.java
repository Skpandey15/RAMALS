package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R1 must stay visible until it is genuinely resolved.
 *
 * <p>R1 — the calibrated performance baseline — has been open since MVP-0 and has a property that
 * makes it uniquely easy to lose: it is not blocked on engineering. No task touches it, every task
 * can pass without it moving, and it will not fail a build. The first moment it would naturally
 * resurface is M1-T18, which is the last moment anybody wants to discover it.
 *
 * <p>So it is pinned. This fails if R1 vanishes from the board, if it stops being marked open, or if
 * it is quietly recorded as closed with no evidence file behind it. Marking a risk closed should
 * take more effort than editing a word in a table.
 */
class Mvp1ReleaseBoardTests {

  private static final Path BOARD = Path.of("..", "docs", "release", "mvp1-release-board.md");

  private static String board() throws IOException {
    assertThat(BOARD).as("the MVP-1 release board").exists();
    return Files.readString(BOARD, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("the release board names R1 and marks it open")
  void r1IsOnTheBoardAndOpen() throws IOException {
    String board = board();

    assertThat(board).as("R1 must appear on the board").contains("R1");
    assertThat(board)
        .as("R1 must be marked open; it blocks the MVP-1 release candidate")
        .containsIgnoringCase("OPEN");
  }

  @Test
  @DisplayName("R1 is stated to block the release candidate")
  void r1IsStatedToBlockTheReleaseCandidate() throws IOException {
    // Naming it is not enough. Somebody reading the board must be told what it stops, or "open"
    // reads as a note rather than a gate.
    assertThat(board())
        .as("the board must say what R1 blocks, not merely that it exists")
        .containsIgnoringCase("blocks M1-T18");
  }

  @Test
  @DisplayName("closing R1 requires evidence, not an edit to a word")
  void closingR1RequiresEvidence() throws IOException {
    String board = board();

    boolean claimsClosed =
        board.contains("R1 — calibrated performance baseline** | ✅")
            || board.contains("R1 — calibrated performance baseline** | 🟢")
            || board.toLowerCase().contains("r1 closed");

    if (!claimsClosed) {
      return;
    }

    // If the board ever claims R1 is closed, there must be a baseline captured somewhere other than
    // a developer workstation. Doc 07 §4 is explicit that developer-machine numbers are indicative
    // only, so a green R1 with nothing behind it would be a worse state than an honest red one.
    Path evidence = Path.of("..", "docs", "release", "evidence", "performance-baseline.md");
    assertThat(evidence).as("R1 cannot be closed without a baseline evidence file").exists();
    assertThat(Files.readString(evidence, StandardCharsets.UTF_8))
        .as("closing R1 requires a baseline from an authoritative environment, not a workstation")
        .doesNotContainIgnoringCase("developer workstation");
  }

  @Test
  @DisplayName("the board tracks every task in the MVP-1 plan")
  void everyTaskIsTracked() throws IOException {
    String board = board();

    // A board missing the later tasks would hide exactly the part of the plan that has not started,
    // which is the part where the remaining decisions and R1 live.
    for (int task = 0; task <= 18; task++) {
      String code = "M1-T%02d".formatted(task);
      assertThat(board).as("the board must track %s", code).contains(code);
    }
  }

  @Test
  @DisplayName("every registered decision is either written or listed as outstanding")
  void everyDecisionIsWrittenOrOwed() throws IOException {
    // Derived rather than hardcoded. A list of "outstanding" ADRs baked into a test goes stale the
    // moment one is written, and then passes because the name still appears somewhere on the page --
    // which is passing for the wrong reason.
    String outstanding = outstandingSection(board());
    Path adrDirectory = Path.of("..", "docs", "adr");

    for (int number : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}) {
      String adr = "M1-ADR-%03d".formatted(number);
      boolean written = adrExists(adrDirectory, adr);
      boolean owed = outstanding.contains(adr);

      assertThat(written || owed)
          .as("%s is neither authored nor listed as outstanding; it would be forgotten", adr)
          .isTrue();
      assertThat(written && owed)
          .as("%s is authored but still listed as outstanding", adr)
          .isFalse();
    }
  }

  /** The paragraph listing decisions still owed, so a written ADR mentioned elsewhere is not
   *  mistaken for an outstanding one. */
  private static String outstandingSection(String board) {
    int start = board.indexOf("Outstanding:");
    assertThat(start).as("the board must list which decisions are still owed").isGreaterThan(-1);
    int end = board.indexOf("\n\n", start);
    return board.substring(start, end > start ? end : board.length());
  }

  private static boolean adrExists(Path directory, String adr) throws IOException {
    try (var entries = Files.list(directory)) {
      return entries.anyMatch(path -> path.getFileName().toString().startsWith(adr));
    }
  }

  @Test
  @DisplayName("no task is started or done while a decision it requires is still open")
  void noTaskRunsAheadOfItsDecision() throws IOException {
    // The operating rule, enforced rather than remembered. A decision not written before its task is
    // still made -- implicitly, by whoever writes the first line of code that assumes an answer --
    // and that is the version nobody reviews.
    String board = board();
    String outstanding = outstandingSection(board);
    Path adrDirectory = Path.of("..", "docs", "adr");

    for (String row : board.lines().toList()) {
      if (!row.startsWith("| M1-T")) {
        continue;
      }
      String[] cells = row.split("\\|");
      if (cells.length < 4) {
        continue;
      }
      String task = cells[1].trim();
      String status = cells[2].trim();
      String gating = cells[3].trim();

      boolean notStarted = status.contains("⬜") && !status.contains("next");
      if (notStarted) {
        continue;
      }

      Matcher required = Pattern.compile("M1-ADR-\\d{3}").matcher(gating);
      while (required.find()) {
        String adr = required.group();
        assertThat(adrExists(adrDirectory, adr))
            .as("%s is started or done but requires %s, which is not authored", task, adr)
            .isTrue();
        assertThat(outstanding.contains(adr))
            .as("%s is started or done but %s is still listed as outstanding", task, adr)
            .isFalse();
      }
    }
  }
}
