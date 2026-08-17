package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The engine must not know which domain it is teaching.
 *
 * <p>Kafka is the first domain, not a permanent one. The same deterministic engine is meant to
 * evaluate a Kafka skill, a school mathematics concept or a professional cloud competency, and that
 * is only true for as long as nothing in the generic core or at the AI boundary refers to Kafka.
 *
 * <p>Today it holds: no production Java source mentions Kafka at all. That is worth asserting
 * precisely because it holds — the cost of losing it is invisible at the moment it happens, in a
 * diff that looks locally reasonable, and it is discovered much later when a second domain is
 * finally attempted and the engine turns out to have opinions.
 *
 * <p>Kafka <em>content</em> is expected and legitimate: seed data in migrations and knowledge assets
 * under {@code knowledge/kafka/} are the first domain package. This test draws the line at
 * behaviour, not at the existence of the word.
 */
class DomainNeutralityTests {

  /** Source trees that must carry no knowledge of any specific domain. */
  private static final List<String> GENERIC_SOURCE_ROOTS = List.of(
      "src/main/java/io/ramals/learningplatform/mastery",
      "src/main/java/io/ramals/learningplatform/evidence",
      "src/main/java/io/ramals/learningplatform/recommendation",
      "src/main/java/io/ramals/learningplatform/learning",
      "src/main/java/io/ramals/learningplatform/curriculum",
      "src/main/java/io/ramals/learningplatform/assessment",
      "src/main/java/io/ramals/learningplatform/learner",
      "src/main/java/io/ramals/learningplatform/ai");

  /**
   * Domain-specific tokens that must not appear in generic code.
   *
   * <p>Lowercased before matching, so {@code Kafka}, {@code KAFKA} and {@code kafka} are all caught.
   */
  private static final List<String> DOMAIN_TOKENS = List.of("kafka", "cbse", "cisce", "btech");

  private static List<Path> genericSources() throws IOException {
    List<Path> sources = new java.util.ArrayList<>();
    for (String root : GENERIC_SOURCE_ROOTS) {
      Path directory = Path.of(root);
      if (!Files.isDirectory(directory)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(directory)) {
        sources.addAll(files.filter(path -> path.toString().endsWith(".java")).sorted().toList());
      }
    }
    return sources;
  }

  @Test
  @DisplayName("no generic core or AI module mentions a specific learning domain")
  void genericModulesNameNoDomain() throws IOException {
    for (Path source : genericSources()) {
      String content = Files.readString(source, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      for (String token : DOMAIN_TOKENS) {
        assertThat(content)
            .as(
                "%s refers to '%s'. The engine must not know which domain it is teaching; route "
                    + "domain specifics through DomainContext and domain packages instead.",
                source, token)
            .doesNotContain(token);
      }
    }
  }

  @Test
  @DisplayName("the AI contract exposes no domain-specific type")
  void theAiBoundaryDeclaresNoDomainSpecificType() throws IOException {
    Path contractPackage = Path.of("src/main/java/io/ramals/learningplatform/ai/contract");
    try (Stream<Path> files = Files.walk(contractPackage)) {
      List<String> names = files
          .filter(path -> path.toString().endsWith(".java"))
          .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
          .toList();

      for (String name : names) {
        for (String token : DOMAIN_TOKENS) {
          assertThat(name)
              .as("the AI boundary must expose no domain-specific type, found %s", name)
              .doesNotContain(token);
        }
      }
      assertThat(names).as("the contract package should contain types").isNotEmpty();
    }
  }

  @Test
  @DisplayName("the canonical contract names no specific domain outside examples")
  void theContractNamesNoDomainOutsideExamples() throws IOException {
    List<String> offending = Files
        .readAllLines(Path.of("..", "contracts", "ai-internal.openapi.yaml"), StandardCharsets.UTF_8)
        .stream()
        // Examples are how a reader understands a field, and an example is not a dependency.
        // Everything else naming a domain would be the schema itself taking a position.
        .filter(line -> !line.contains("examples:") && !line.trim().startsWith("#"))
        .filter(line -> {
          String lower = line.toLowerCase(Locale.ROOT);
          return DOMAIN_TOKENS.stream().anyMatch(lower::contains);
        })
        .toList();

    assertThat(offending)
        .as("the contract schema must not name a specific domain outside examples and comments")
        .isEmpty();
  }

  @Test
  @DisplayName("the scan actually reads the source it claims to cover")
  void theScanCoversRealSources() throws IOException {
    // Without this, deleting or renaming a package would silently empty the scan and the suite would
    // still be green -- a guard that observes nothing is indistinguishable from a guard that passes.
    List<Path> sources = genericSources();

    assertThat(sources)
        .as("the generic source scan found almost nothing; the roots are probably wrong")
        .hasSizeGreaterThan(30);
    assertThat(sources.stream().map(Path::toString))
        .anyMatch(path -> path.contains("mastery"))
        .anyMatch(path -> path.contains("ai"));
  }

  @Test
  @DisplayName("Kafka remains present as domain content, which is the point")
  void kafkaSurvivesWhereItBelongs() {
    // The rule is "no domain knowledge in the engine", not "no Kafka in the repository". If this
    // ever fails, the previous tests are passing because the first domain has been deleted rather
    // than because the engine is neutral.
    assertThat(Path.of("..", "knowledge", "kafka"))
        .as("Kafka should still exist as a domain package")
        .exists();
  }
}
