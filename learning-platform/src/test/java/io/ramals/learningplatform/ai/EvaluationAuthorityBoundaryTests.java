package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M1-ADR-010: an AI evaluation never becomes an authoritative score.
 *
 * <p>The rule already holds for a reason no code change can undo — {@code ramals_ai_runtime} has no
 * privilege on {@code ledger} (V015, proven by {@link
 * io.ramals.learningplatform.security.AiRuntimeBoundaryIntegrationTests}), and the AI plane holds no
 * database connection at all. This test is about the other side of the boundary: the Spring code
 * that <em>does</em> hold the credential.
 *
 * <p>What it defends is a shape rather than a behaviour, and it is written now rather than when the
 * evaluate client arrives. The failure being prevented is a diff that looks entirely reasonable in
 * isolation: an AI component gaining a reference to the evidence repository "to record the formative
 * result for reporting", and evidence rows appearing from a source the mastery engine cannot
 * distinguish from a real answer. At that point nothing throws and no test fails — the platform just
 * quietly starts measuring learners on something a model said.
 *
 * <p>Asserting it while it holds is the point. Afterwards there is nothing to assert.
 */
class EvaluationAuthorityBoundaryTests {

  private static final Path AI_SOURCES = Path.of("src/main/java/io/ramals/learningplatform/ai");

  private static final Path PRODUCTION_SOURCES = Path.of("src/main/java");

  /**
   * The packages that own authoritative learner state.
   *
   * <p>Named by package rather than by class, so a new writer inside one of them is covered the day
   * it is written rather than the day somebody remembers to add it here.
   */
  private static final List<String> AUTHORITATIVE_PACKAGES = List.of(
      "io.ramals.learningplatform.evidence",
      "io.ramals.learningplatform.mastery",
      "io.ramals.learningplatform.confidence",
      "io.ramals.learningplatform.recommendation",
      "io.ramals.learningplatform.learning");

  /**
   * The suffixes of the types that <em>write</em>.
   *
   * <p>The line is drawn at writers, not at the packages, because reading authoritative state is
   * what a deterministic gate is supposed to do: {@code DiagnosticProposalGate} imports {@code
   * MasteryStatus} precisely so it can refuse a proposal that ignores what the platform already
   * measured. Forbidding that would push the gate toward deciding on less information, which is the
   * opposite of the intent.
   *
   * <p>What must never appear is a handle on the thing that persists — a repository or an engine.
   * Those are how a formative result becomes a row.
   */
  private static final List<String> WRITER_SUFFIXES = List.of("Repository", "Engine", "Service");

  private static List<Path> javaSourcesUnder(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static String read(Path path) throws IOException {
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  // -- the AI boundary cannot reach the authoritative engines --------------------------------------

  @Test
  @DisplayName("no AI component imports something that writes authoritative learner state")
  void aiCodeCannotReachTheAuthoritativeWriters() throws IOException {
    List<String> offenders = new ArrayList<>();

    for (Path source : javaSourcesUnder(AI_SOURCES)) {
      for (String imported : importsIn(read(source))) {
        boolean authoritative =
            AUTHORITATIVE_PACKAGES.stream().anyMatch(pkg -> imported.startsWith(pkg + "."));
        boolean writes = WRITER_SUFFIXES.stream().anyMatch(imported::endsWith);
        if (authoritative && writes) {
          offenders.add(source.getFileName() + " imports " + imported);
        }
      }
    }

    // Not "the AI plane cannot write evidence" -- that is the privilege model's job and is proven
    // elsewhere. This is narrower and complementary: the Spring code that holds the credential must
    // not be reachable from the code that talks to a model.
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("reading authoritative state is allowed, and something does")
  void aiCodeMayReadWhatThePlatformAlreadyMeasured() throws IOException {
    List<String> readers = new ArrayList<>();

    for (Path source : javaSourcesUnder(AI_SOURCES)) {
      for (String imported : importsIn(read(source))) {
        boolean authoritative =
            AUTHORITATIVE_PACKAGES.stream().anyMatch(pkg -> imported.startsWith(pkg + "."));
        if (authoritative && WRITER_SUFFIXES.stream().noneMatch(imported::endsWith)) {
          readers.add(source.getFileName() + " reads " + imported);
        }
      }
    }

    // Recorded as a positive expectation so the distinction survives. A future tightening that
    // banned the whole package would make the deterministic gate decide on less information than
    // the platform holds, and this test says why that would be a regression rather than a fix.
    assertThat(readers).isNotEmpty();
  }

  private static List<String> importsIn(String content) {
    return content.lines()
        .map(String::strip)
        .filter(line -> line.startsWith("import ") && line.endsWith(";"))
        .map(line -> line.substring("import ".length(), line.length() - 1).strip())
        .map(line -> line.startsWith("static ") ? line.substring("static ".length()) : line)
        .toList();
  }

  @Test
  @DisplayName("no AI component names an authoritative table")
  void aiCodeCannotNameAnAuthoritativeTable() throws IOException {
    List<String> offenders = new ArrayList<>();

    for (Path source : javaSourcesUnder(AI_SOURCES)) {
      String content = read(source);
      for (String table : List.of(
          "ledger.evidence", "ledger.mastery_snapshot", "ledger.decision_record",
          "core.learner_skill_aggregate", "core.assessment_response")) {
        if (content.contains(table)) {
          offenders.add(source.getFileName() + " references " + table);
        }
      }
    }

    // Catches the case the import check would miss: raw SQL against the table without going through
    // the repository at all.
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("the AI source tree is not empty, so the checks above are checking something")
  void thereIsAiCodeToCheck() throws IOException {
    // Without this, deleting the ai package would turn both tests above green.
    assertThat(javaSourcesUnder(AI_SOURCES)).hasSizeGreaterThan(5);
  }

  // -- evidence has exactly one writer ---------------------------------------------------------------

  @Test
  @DisplayName("only the evidence repository writes ledger.evidence")
  void evidenceHasASingleWriter() throws IOException {
    List<String> writers = new ArrayList<>();

    for (Path source : javaSourcesUnder(PRODUCTION_SOURCES)) {
      String content = read(source);
      if (content.contains("INSERT INTO ledger.evidence")) {
        writers.add(source.getFileName().toString());
      }
    }

    // One writer is what makes "evidence comes from scoring a learner's answer" a checkable claim
    // rather than a description. A second writer would not have to be malicious to break it.
    assertThat(writers).containsExactly("EvidenceRepository.java");
  }

  @Test
  @DisplayName("the evidence writer is not reachable from the AI package")
  void theEvidenceWriterIsNotAnAiDependency() throws IOException {
    List<String> offenders = new ArrayList<>();

    for (Path source : javaSourcesUnder(AI_SOURCES)) {
      if (read(source).contains("EvidenceRepository")) {
        offenders.add(source.getFileName().toString());
      }
    }

    assertThat(offenders).isEmpty();
  }

  // -- the trust levels an AI response may carry ------------------------------------------------------

  @Test
  @DisplayName("no AI component treats VERIFIED_CONTENT as evaluation authority")
  void verifiedContentIsAboutContentNotEvaluation() throws IOException {
    // M1-ADR-010 draws this exact distinction, and it is the one most likely to be lost: content
    // that has passed review may enter the scoring path, but an evaluation of that content never
    // becomes the score. A file that both mentions the verified state and writes evidence would be
    // the place the two got confused.
    for (Path source : javaSourcesUnder(AI_SOURCES)) {
      String content = read(source);
      assertThat(content.contains("VERIFIED_CONTENT") && content.contains("Evidence"))
          .as("%s", source.getFileName())
          .isFalse();
    }
  }
}
