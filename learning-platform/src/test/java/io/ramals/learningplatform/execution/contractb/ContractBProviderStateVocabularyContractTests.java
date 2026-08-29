package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two halves of Contract B must agree on the words they use for a provider state.
 *
 * <p>This test exists because they did not. The Python adapter maps Anthropic's
 * {@code processing_status: "ended"} to {@code RESULT_AVAILABLE}; the Java lifecycle's switch
 * matched {@code SUCCEEDED}, {@code ENDED} and {@code COMPLETED}. No overlap — so every real
 * Contract B execution reached a finished batch, failed to recognise it, and polled forever.
 *
 * <p>Fifty-one green tests did not catch it, because the fake provider set {@code "SUCCEEDED"}: it
 * asserted the vocabulary I had assumed rather than the one the adapter emits. A fake that agrees
 * with the assumption instead of the implementation tests nothing at the boundary, and the boundary
 * was where the bug lived. It took a real provider to find it.
 *
 * <p>So this reads the adapter's own mapping table out of the Python source and asserts the
 * lifecycle handles every value in it. Reading the source rather than restating the values is the
 * point: a copy would be a third place for the vocabulary to drift.
 */
class ContractBProviderStateVocabularyContractTests {

  private static final Path ADAPTER = Path.of("..", "ramals-ai", "src", "ramals_ai", "gateway",
      "providers", "anthropic_batches_adapter.py");
  private static final Path LIFECYCLE = Path.of("src", "main", "java", "io", "ramals",
      "learningplatform", "execution", "contractb", "ContractBExecutionService.java");

  /** The values of {@code _STATE_BY_PROCESSING_STATUS}, read from the adapter itself. */
  private static List<String> adapterStates() throws IOException {
    String source = Files.readString(ADAPTER, StandardCharsets.UTF_8);
    int start = source.indexOf("_STATE_BY_PROCESSING_STATUS");
    assertThat(start).as("the adapter must still declare its state mapping").isNotNegative();
    String table = source.substring(start, source.indexOf('}', start));

    List<String> states = new ArrayList<>();
    Matcher entry = Pattern.compile("\"[^\"]+\"\\s*:\\s*\"([A-Z_]+)\"").matcher(table);
    while (entry.find()) {
      states.add(entry.group(1));
    }
    assertThat(states).as("the mapping must not be empty").isNotEmpty();
    return states;
  }

  /**
   * States that mean "still working", for which falling through to the polling default is correct.
   *
   * <p>Listed rather than inferred, so adding a provider state forces somebody to decide which side
   * it belongs on. That decision is the thing this test exists to compel: the bug was not a missing
   * case label, it was a terminal state nobody had classified.
   */
  private static final List<String> NON_TERMINAL_BY_DESIGN = List.of("RUNNING", "CANCELLING");

  @Test
  @DisplayName("every provider state the adapter can emit is a deliberate decision")
  void everyAdapterStateIsDeliberatelyClassified() throws IOException {
    String lifecycle = Files.readString(LIFECYCLE, StandardCharsets.UTF_8);

    for (String state : adapterStates()) {
      boolean explicitCase = lifecycle.contains("case \"" + state + "\"")
          || lifecycle.contains("\"" + state + "\",");
      boolean deliberatelyNonTerminal = NON_TERMINAL_BY_DESIGN.contains(state);

      assertThat(explicitCase || deliberatelyNonTerminal)
          .as("'%s' is emitted by the adapter and is classified neither as a terminal case nor as "
              + "deliberately non-terminal. An unclassified state falls through to the polling "
              + "default and the execution never finishes -- which is exactly the defect the W2 "
              + "real-provider run found.", state)
          .isTrue();
    }
  }

  @Test
  @DisplayName("the adapter's terminal state reaches result retrieval, not the polling default")
  void theEndedStateIsTerminal() throws IOException {
    // The specific failure: 'ended' must reach retrieveAndFinish. Asserted on the branch rather
    // than merely on the string appearing somewhere in the file, because appearing in a comment
    // would satisfy the test above while leaving the bug in place.
    assertThat(adapterStates())
        .as("'ended' is the state a finished Anthropic batch reports")
        .contains("RESULT_AVAILABLE");

    String lifecycle = Files.readString(LIFECYCLE, StandardCharsets.UTF_8);
    int branch = lifecycle.indexOf("case \"RESULT_AVAILABLE\"");
    assertThat(branch).as("RESULT_AVAILABLE must be a switch case").isNotNegative();
    assertThat(lifecycle.substring(branch, Math.min(branch + 220, lifecycle.length())))
        .as("the terminal branch must retrieve the result")
        .contains("retrieveAndFinish");
  }

  @Test
  @DisplayName("the fake speaks the adapter's vocabulary, so tests exercise the real boundary")
  void theFakeUsesTheAdapterVocabulary() throws IOException {
    // A fake free to invent state names is a fake that agrees with whatever the author assumed.
    // That is exactly how this bug survived, so the fake is pinned to the adapter's words.
    Path fake = Path.of("src", "test", "java", "io", "ramals", "learningplatform", "execution",
        "contractb", "FakeDurableExecutionPort.java");
    String source = Files.readString(fake, StandardCharsets.UTF_8);

    assertThat(source)
        .as("the fake must report success using the state the adapter actually emits")
        .contains("\"RESULT_AVAILABLE\"");
  }
}
