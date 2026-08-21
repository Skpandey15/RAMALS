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
    // Normalised because the parsing below is line-ending sensitive and this file is edited on
    // Windows. A board saved with CRLF made the "Outstanding:" paragraph run to end of file, which
    // surfaced as a confident and entirely wrong claim that an authored ADR was still outstanding.
    // A governance gate that fails for a reason unrelated to governance teaches people to ignore it.
    return Files.readString(BOARD, StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  /**
   * Whether the board claims R1 is resolved.
   *
   * <p>Deliberately generous in what it recognises as a closure claim: every plausible way of
   * writing one counts, so the strict checks below cannot be sidestepped by choosing a different
   * tick.
   */
  private static boolean claimsR1Closed(String board) {
    return board.contains("R1 — calibrated performance baseline** | ✅")
        || board.contains("R1 — calibrated performance baseline** | 🟢")
        || board.toLowerCase(java.util.Locale.ROOT).contains("r1 closed");
  }

  @Test
  @DisplayName("the release board names R1, and marks it open until it is genuinely closed")
  void r1IsOnTheBoardAndOpen() throws IOException {
    String board = board();

    assertThat(board).as("R1 must appear on the board").contains("R1");

    if (!claimsR1Closed(board)) {
      assertThat(board)
          .as("R1 must be marked open; it blocks the MVP-1 release candidate")
          .containsIgnoringCase("OPEN");
    }
  }

  @Test
  @DisplayName("while R1 is open, the board says what it blocks")
  void r1IsStatedToBlockTheReleaseCandidate() throws IOException {
    String board = board();

    // Naming it is not enough. Somebody reading the board must be told what it stops, or "open"
    // reads as a note rather than a gate.
    //
    // Only while it is open. Once R1 closes the sentence is no longer true, and a gate that forces
    // the board to keep asserting a resolved blocker is a gate that teaches people to write things
    // that are not so.
    if (claimsR1Closed(board)) {
      return;
    }
    assertThat(board)
        .as("the board must say what R1 blocks, not merely that it exists")
        .containsIgnoringCase("blocks M1-T18");
  }

  @Test
  @DisplayName("closing R1 requires a calibrated baseline, not an edit to a word")
  void closingR1RequiresEvidence() throws IOException {
    String board = board();

    if (!claimsR1Closed(board)) {
      return;
    }

    // R1 asks for a calibrated baseline, so the file this points at is the calibrated one. It used
    // to point at the MVP-0 evidence, which is an honest record of an indicative workstation run and
    // could never satisfy R1 — checking it for the absence of the words "developer workstation" was
    // therefore a check that could only ever be failed by the very document it named.
    //
    // What replaces it asks for the properties that make a baseline calibrated rather than for the
    // absence of a phrase.
    Path evidence =
        Path.of("..", "docs", "release", "evidence", "r1-calibrated-baseline.md");
    assertThat(evidence).as("R1 cannot be closed without a calibrated baseline record").exists();

    String record = Files.readString(evidence, StandardCharsets.UTF_8).replace("\r\n", "\n");

    assertThat(record)
        .as("the baseline must name the environment spec it was measured against")
        .contains("perf-standard-01");
    assertThat(record)
        .as("the baseline must record that the environment attested as conforming")
        .containsIgnoringCase("conforms");
    assertThat(record)
        .as("Doc 07 §4: a workstation run is indicative only and cannot close R1")
        .doesNotContainIgnoringCase("measured on a developer workstation");
    assertThat(record)
        .as(
            "a baseline taken with the rate-limit override relaxed measures capacity, not the "
                + "platform's own policy, and cannot be the authoritative result on its own")
        .containsIgnoringCase("production");

    assertThat(board)
        .as("the board must link the evidence that closes R1, so the claim is one click from proof")
        .contains("evidence/r1-calibrated-baseline.md");
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
    String board = board();
    String outstanding = outstandingSection(board);
    Path adrDirectory = Path.of("..", "docs", "adr");

    // Derived from what the board and the directory actually name, rather than a second copy of the
    // mapping. Rule: the board owns task -> decision; this test reads it, and never restates it.
    java.util.Set<String> registered = new java.util.TreeSet<>();
    Matcher onBoard = Pattern.compile("M1-ADR-\\d{3}").matcher(board);
    while (onBoard.find()) {
      registered.add(onBoard.group());
    }
    try (var entries = Files.list(adrDirectory)) {
      entries
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith("M1-ADR-"))
          .map(name -> name.substring(0, "M1-ADR-000".length()))
          .forEach(registered::add);
    }
    assertThat(registered).as("the board and ADR directory should name some decisions").isNotEmpty();

    for (String adr : registered) {
      boolean written = adrAccepted(adrDirectory, adr);
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

  /**
   * Whether a decision is settled, not merely filed.
   *
   * <p>An ADR file that exists is not a decision that has been made. A draft is a decision still
   * being argued, and a task started against one is a task making that decision implicitly in code.
   * The gate reads the status line.
   */
  private static boolean adrAccepted(Path directory, String adr) throws IOException {
    Path file;
    try (var entries = Files.list(directory)) {
      file = entries
          .filter(path -> path.getFileName().toString().startsWith(adr))
          .findFirst()
          .orElse(null);
    }
    if (file == null) {
      return false;
    }
    return Files.readString(file, StandardCharsets.UTF_8)
        .lines()
        .filter(line -> line.startsWith("- **Status:**"))
        .anyMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("accepted"));
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
        assertThat(adrAccepted(adrDirectory, adr))
            .as("%s is started or done but requires %s, which is not Accepted", task, adr)
            .isTrue();
        assertThat(outstanding.contains(adr))
            .as("%s is started or done but %s is still listed as outstanding", task, adr)
            .isFalse();
      }
    }
  }
}
