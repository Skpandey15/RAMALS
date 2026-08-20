package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MVP-1 baseline record says only what was actually measured.
 *
 * <p>A baseline is the reference point MVP-2 will be compared against, so its failure mode is not
 * being wrong — it is being <em>optimistic</em>. An unmeasured dimension rendered as a pass produces
 * a later comparison that looks rigorous and is meaningless, and by then the run that would have
 * caught it is a year old.
 *
 * <p>M1-ADR-009 requires this check by name: an unmeasured dimension must never be rendered as a
 * pass. The quality rubrics cannot be scored on {@code ci-fake}, which returns a deterministic canned
 * string, so any number computed from it describes the fake rather than the model.
 */
class Mvp1BaselineRecordTests {

  private static final Path BASELINE = Path.of("..", "docs", "release", "mvp1-baseline.md");
  private static final Path BOARD = Path.of("..", "docs", "release", "mvp1-release-board.md");

  /** The seven identifiers that define every consequential deterministic decision. */
  private static final List<String> FROZEN_ENGINES = List.of(
      "DIAGNOSTIC_SCORING_V1", "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V1",
      "MASTERY_STATUS_POLICY_V1", "RECOMMENDATION_POLICY_V1", "PROGRESSION_POLICY_V1",
      "SESSION_POLICY_V1");

  /** Dimensions that cannot be measured without an authoritative environment or a real route. */
  private static final List<String> UNMEASURED_DIMENSIONS = List.of(
      "Latency and throughput baseline",
      "Primary task functional rubric",
      "Tutor pedagogical rubric",
      "Regression vs approved baseline");

  private static String baseline() throws IOException {
    assertThat(BASELINE).as("the MVP-1 baseline record").exists();
    return Files.readString(BASELINE, StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  private static String board() throws IOException {
    return Files.readString(BOARD, StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  /** The section listing what has no measurement, which is the half that gets quietly deleted. */
  private static String notMeasuredSection(String record) {
    int start = record.indexOf("## Not measured");
    assertThat(start)
        .as("the baseline must state what it did not measure, not only what it did")
        .isGreaterThan(-1);
    int end = record.indexOf("\n## ", start + 1);
    return end > start ? record.substring(start, end) : record.substring(start);
  }

  // -- what is absent stays visible ------------------------------------------------------------------

  @Test
  @DisplayName("every unmeasured dimension is named in the unmeasured section")
  void unmeasuredDimensionsAreNamed() throws IOException {
    String unmeasured = notMeasuredSection(baseline());

    for (String dimension : UNMEASURED_DIMENSIONS) {
      assertThat(unmeasured)
          .as("%s has no measurement and the baseline must say so", dimension)
          .contains(dimension);
    }
  }

  @Test
  @DisplayName("no unmeasured dimension is rendered as a pass")
  void nothingUnmeasuredIsRenderedAsAPass() throws IOException {
    String unmeasured = notMeasuredSection(baseline()).toLowerCase(Locale.ROOT);

    // Required by M1-ADR-009. The words that turn an absence into a claim: a row that says "passed"
    // or "met" reads identically to a measured one once it is quoted in a release note.
    for (String claim : List.of("| passed", "| pass |", "| met |", "✅ measured", "meets threshold")) {
      assertThat(unmeasured)
          .as("an unmeasured dimension must not be presented as satisfied (%s)", claim)
          .doesNotContain(claim);
    }
  }

  @Test
  @DisplayName("R1 is recorded as outstanding for as long as the board says it is open")
  void r1StaysVisibleWhileItIsOpen() throws IOException {
    if (!board().contains("**R1")) {
      return;
    }

    // The board and the baseline must not disagree about whether the platform has a calibrated
    // performance measurement. R1 closing is the event that adds numbers here.
    assertThat(baseline())
        .as("the board still shows R1, so the baseline must not imply a latency measurement exists")
        .contains("R1");
    assertThat(notMeasuredSection(baseline()))
        .as("R1 is why the latency baseline is absent, and that belongs in the unmeasured section")
        .contains("R1");
  }

  // -- what is present is the real thing ---------------------------------------------------------------

  @Test
  @DisplayName("the baseline pins every frozen engine identifier")
  void everyFrozenEngineIsRecorded() throws IOException {
    String record = baseline();

    // A baseline that named six of seven engines would let the seventh change without the reference
    // point noticing, which is the same failure EngineVersionFreezeTests exists to prevent.
    for (String engine : FROZEN_ENGINES) {
      assertThat(record).as("%s must appear in the baseline", engine).contains(engine);
    }
  }

  @Test
  @DisplayName("the baseline records the agent identity M1-ADR-009 compares against")
  void agentIdentityIsRecorded() throws IOException {
    String record = baseline();

    // M1-ADR-009 identifies an evaluation baseline by agentVersion, promptVersion and modelRoute.
    // Without all three here, a future comparison cannot establish what it is comparing to.
    for (String agent : List.of("TUTOR", "DIAGNOSTIC", "ASSESSMENT", "ADAPTATION")) {
      assertThat(record).contains(agent + "_AGENT_V1");
      assertThat(record).contains(agent + "_PROMPT_V1");
    }
  }

  @Test
  @DisplayName("the baseline states where it was measured")
  void theMeasurementEnvironmentIsStated() throws IOException {
    // The same number means different things on a workstation and on a fixed-spec environment, and
    // a baseline that omits which one it came from invites the wrong comparison.
    assertThat(baseline())
        .as("a measurement without its environment is not reproducible")
        .contains("developer workstation");
  }
}
