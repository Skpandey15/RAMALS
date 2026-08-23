package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Keeps the M2-T01 architecture decision and its machine-readable contracts reviewable. */
class Mvp2ContractTests {

  private static final Path REPOSITORY = Path.of("..");
  private static final Path CONTRACTS = REPOSITORY.resolve("contracts/mvp2");
  private static final Path FREEZE =
      REPOSITORY.resolve("docs/architecture/mvp2-t01-contract-freeze.md");
  private static final Path ADR_REGISTER = REPOSITORY.resolve("docs/adr/M2-ADR-register.md");

  private static final List<String> SCHEMAS = List.of(
      "agent-work.v1.schema.json",
      "grounded-context.v1.schema.json",
      "diagnostic-proposal.v1.schema.json",
      "assessment-evaluation-proposal.v1.schema.json");

  @Test
  @DisplayName("M2-T01 records all seven integration questions as resolved")
  void sevenQuestionAnalysisIsResolved() throws IOException {
    String freeze = Files.readString(FREEZE, StandardCharsets.UTF_8);

    assertThat(freeze).contains("**Status:** Accepted");
    assertThat(freeze).contains("## Seven-question integration resolution");
    assertThat(freeze.lines().filter(line -> line.endsWith("| Resolved |"))).hasSize(7);
    assertThat(freeze).contains("T08 is hard-blocked until T02, T03, T04, and T07 pass");
  }

  @Test
  @DisplayName("all fifteen MVP-2 ADRs are accepted and task-mapped")
  void allMvp2DecisionsAreAccepted() throws IOException {
    String register = Files.readString(ADR_REGISTER, StandardCharsets.UTF_8);

    assertThat(register).contains("**Status:** Accepted for MVP-2 implementation");
    for (int number = 1; number <= 15; number++) {
      assertThat(register).contains("M2-ADR-%03d".formatted(number));
    }
  }

  @Test
  @DisplayName("frozen MVP-2 schemas are strict, versioned, and internally resolvable")
  void frozenSchemasAreStrictAndInternallyResolvable() throws IOException {
    Pattern references = Pattern.compile("\\\"\\$ref\\\"\\s*:\\s*\\\"#/\\$defs/([^\\\"]+)\\\"");

    for (String filename : SCHEMAS) {
      Path path = CONTRACTS.resolve(filename);
      assertThat(path).exists();

      String schema = Files.readString(path, StandardCharsets.UTF_8);
      assertThat(schema).contains("https://json-schema.org/draft/2020-12/schema");
      assertThat(schema).contains("\"type\": \"object\"");
      assertThat(schema).contains("\"additionalProperties\": false");
      assertThat(schema).contains("\"contractVersion\": { \"const\": \"1.0\" }");

      Matcher matcher = references.matcher(schema);
      while (matcher.find()) {
        assertThat(schema)
            .as("%s resolves #/$defs/%s", filename, matcher.group(1))
            .contains("\"%s\": {".formatted(matcher.group(1)));
      }
    }
  }

  @Test
  @DisplayName("assessment evidence remains v1-compatible and is mandatory at the Spring gate")
  void assessmentEvaluationSchemaPreservesFrozenOptionalEvidence() throws IOException {
    String schema =
        Files.readString(
            CONTRACTS.resolve("assessment-evaluation-proposal.v1.schema.json"),
            StandardCharsets.UTF_8);

    assertThat(schema)
        .contains(
            "\"dimensions\", \"feedback\", \"confidence\"")
        .doesNotContain(
            "\"dimensions\", \"feedback\", \"evidenceIds\", \"confidence\"")
        .contains("\"evidenceIds\": { \"type\": \"array\", \"maxItems\": 64");
  }
}
