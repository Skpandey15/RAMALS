package io.ramals.learningplatform.execution.contractb;

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
 * The submission marker means the same thing on both sides of the AI-plane boundary.
 *
 * <p>A string constant duplicated across two languages is exactly the kind of agreement that rots
 * quietly. If the AI plane started emitting a different spelling, this side would stop recognising
 * any refusal — every definite failure would silently become ambiguous. Safe, and wrong: the ledger
 * would fill with executions needing an operator that never needed one, and nobody would be told.
 *
 * <p>So the value is read out of the Python source rather than restated here, the same way
 * {@code ContractBProviderStateVocabularyContractTests} reads the provider's state vocabulary. That
 * test exists because a fake agreeing with an assumption instead of the implementation is how W2
 * shipped three defects; this one exists for the same reason.
 */
class ContractBSubmissionMarkerContractTests {

  private static final Path DURABLE_ROUTER =
      Path.of("..", "ramals-ai", "src", "ramals_ai", "api", "durable.py");

  private static String pythonConstant(String name) throws IOException {
    String source = Files.readString(DURABLE_ROUTER, StandardCharsets.UTF_8);
    Matcher matcher =
        Pattern.compile(name + "\\s*=\\s*\"([^\"]+)\"").matcher(source);
    assertThat(matcher.find())
        .as("%s must be defined in %s", name, DURABLE_ROUTER)
        .isTrue();
    return matcher.group(1);
  }

  @Test
  @DisplayName("the platform's NOT_CREATED marker is the one the AI plane emits")
  void theMarkerMatchesTheAiPlane() throws IOException {
    assertThat(RamalsAiDurableExecutionClient.SUBMISSION_NOT_CREATED)
        .as("a drifted marker turns every definite refusal into an ambiguous one, silently")
        .isEqualTo(pythonConstant("SUBMISSION_NOT_CREATED"));
  }

  @Test
  @DisplayName("the two dispositions remain distinct")
  void theDispositionsAreDistinct() throws IOException {
    // If these ever collapsed to one value the classifier would answer the same way for both, and
    // the distinction the whole taxonomy rests on would disappear without a compile error.
    assertThat(pythonConstant("SUBMISSION_NOT_CREATED"))
        .isNotEqualTo(pythonConstant("SUBMISSION_MAY_EXIST"));
  }

  @Test
  @DisplayName("the AI plane still refuses to rule creation out for transport failures")
  void transportFailuresAreNotInTheRuledOutSet() throws IOException {
    String source = Files.readString(DURABLE_ROUTER, StandardCharsets.UTF_8);
    // The set literal only. The prose after it names these codes precisely to say why they are
    // excluded, so a window that ran to the next definition would read the explanation as the rule.
    int opens = source.indexOf("_CREATION_RULED_OUT");
    int closes = source.indexOf("# Deliberately absent", opens);
    assertThat(closes).as("the set and the prose explaining it must stay distinguishable")
        .isGreaterThan(opens);
    String ruledOut = source.substring(opens, closes);

    // The load-bearing half of the taxonomy is what is *absent* from that set. Adding either of
    // these would make a timeout look like proof that nothing was created, which is the S2 defect
    // reintroduced one layer further in, where no Java test would see it.
    assertThat(ruledOut)
        .as("a timeout is the absence of an answer, never an answer")
        .doesNotContain("PROVIDER_TIMEOUT");
    assertThat(ruledOut)
        .as("a reset or a provider 5xx can follow work that already began")
        .doesNotContain("PROVIDER_UNAVAILABLE");
  }
}
