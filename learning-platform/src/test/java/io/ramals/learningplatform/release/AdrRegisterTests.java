package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ADR index must describe the ADRs that actually exist.
 *
 * <p>The index carries two claims: a table of adopted decisions, and a line naming the ones still
 * unwritten. Both are maintained by hand, and each M1 task is gated on the ADR the package register
 * assigns to it. An index that disagrees with the directory is worse than no index -- it is the
 * thing a reader consults to decide whether a task is unblocked.
 *
 * <p>Cheap to check, and it fails the moment an ADR is authored without being listed, or listed as
 * missing after it has been written.
 */
class AdrRegisterTests {

  private static final Pattern ADR_FILE = Pattern.compile("^(M1-ADR-\\d{3})-.*\\.md$");
  private static final Pattern UNAUTHORED_NUMBER = Pattern.compile("\\b0?(\\d{2,3})\\b");

  private static Path adrDirectory() {
    return Path.of("..", "docs", "adr");
  }

  private static String index() throws IOException {
    return Files.readString(adrDirectory().resolve("README.md"), StandardCharsets.UTF_8);
  }

  /** Every authored M1 ADR, by identifier, taken from the files rather than from any list. */
  private static List<String> authoredAdrs() throws IOException {
    try (Stream<Path> entries = Files.list(adrDirectory())) {
      return entries
          .map(path -> path.getFileName().toString())
          .map(ADR_FILE::matcher)
          .filter(Matcher::matches)
          .map(matcher -> matcher.group(1))
          .sorted()
          .toList();
    }
  }

  /** The sentence listing what has not been written yet, which is the half that goes stale. */
  private static String unauthoredSentence(String index) {
    int start = index.indexOf("Not yet authored:");
    assertThat(start).as("the index must state which ADRs remain unwritten").isGreaterThan(-1);
    int end = index.indexOf("\n\n", start);
    return index.substring(start, end > start ? end : index.length());
  }

  @Test
  @DisplayName("every authored ADR is listed in the index table")
  void everyAuthoredAdrIsListed() throws IOException {
    String index = index();
    for (String adr : authoredAdrs()) {
      assertThat(index)
          .as("%s exists on disk but the index does not mention it", adr)
          .contains(adr);
    }
  }

  @Test
  @DisplayName("every authored ADR links to a file that exists")
  void everyIndexedAdrLinkResolves() throws IOException {
    Matcher links = Pattern.compile("\\]\\((M1-ADR-[^)]+\\.md)\\)").matcher(index());
    int checked = 0;
    while (links.find()) {
      String target = links.group(1);
      assertThat(adrDirectory().resolve(target))
          .as("the index links %s, which does not exist", target)
          .exists();
      checked++;
    }
    assertThat(checked).as("the index should link at least one ADR").isPositive();
  }

  @Test
  @DisplayName("the ADR index does not restate which task each decision gates")
  void theIndexDoesNotDuplicateTheTaskMapping() throws IOException {
    // One canonical owner: the release board maps task to decision. This index says what each
    // decision *is*.
    //
    // Two copies of a mapping is a mapping that will disagree with itself, and the copy a reader
    // happens to open is the one they believe. Enforced rather than asked for, because restating it
    // here is the natural thing to do while editing an ADR entry -- it reads as helpful.
    List<String> offending = Files
        .readAllLines(adrDirectory().resolve("README.md"), StandardCharsets.UTF_8).stream()
        .filter(line -> line.startsWith("|"))
        .filter(line -> line.contains("M1-T"))
        .toList();

    assertThat(offending)
        .as("the task -> decision mapping belongs to docs/release/mvp1-release-board.md alone")
        .isEmpty();
  }

  @Test
  @DisplayName("nothing already written is still listed as unwritten")
  void unauthoredListExcludesWhatHasBeenWritten() throws IOException {
    String sentence = unauthoredSentence(index());
    List<String> authored = authoredAdrs();

    Matcher numbers = UNAUTHORED_NUMBER.matcher(sentence);
    while (numbers.find()) {
      String identifier = "M1-ADR-%03d".formatted(Integer.parseInt(numbers.group(1)));
      assertThat(authored)
          .as("%s is listed as not yet authored, but the file exists", identifier)
          .doesNotContain(identifier);
    }
  }
}
