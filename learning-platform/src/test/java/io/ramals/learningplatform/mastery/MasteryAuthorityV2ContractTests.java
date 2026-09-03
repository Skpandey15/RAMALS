package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Mastery, confidence, and coverage are computed, never accepted.
 *
 * <p>V2 makes coverage consequential: objective coverage is 35% of the confidence blend and band
 * coverage gates MASTERED outright. That raises the stakes on a property the platform has always
 * relied on and never checked -- that none of these values can be supplied from outside. A request
 * body that could name its own covered objectives would be a request body that can award mastery.
 *
 * <p>Checked against the source of the delivery layer rather than by exercising one endpoint,
 * because the risk is a route nobody thought to test.
 */
class MasteryAuthorityV2ContractTests {

  /** Names that would represent a client-supplied engine output if they appeared in a request. */
  private static final List<String> ENGINE_OUTPUTS = List.of(
      "masteryScore", "masteryStatus", "evidenceConfidence", "objectiveCoverage",
      "coveredDifficultyBands", "coveredObjectiveIds", "normalizedScore", "observedScore");

  /**
   * The one request body that names an engine output, and the reason it is tolerated.
   *
   * <p>{@code TutorExplainRequest.masteryStatus} is prompt context: it reaches
   * {@code LearningContext} and travels to the AI plane to steer the tone of an explanation. It
   * touches no evidence row, no snapshot, and no engine. A learner who claims MASTERED there gets
   * a differently pitched explanation and nothing else -- which is a real if minor hole, worth
   * naming here rather than leaving for someone to rediscover, and worth fixing by reading the
   * learner's actual status server-side rather than accepting theirs.
   */
  private static final String TUTOR_PROMPT_CONTEXT = "TutorExplainRequest.java";

  @Test
  void noRequestTypeAcceptsAnEngineOutputThatCouldReachTheLedger() throws IOException {
    List<String> offenders = new ArrayList<>();
    List<String> promptContextOnly = new ArrayList<>();
    for (Path file : mainSources()) {
      String name = file.getFileName().toString();
      if (!name.endsWith("Request.java")) {
        continue;
      }
      String source = Files.readString(file, StandardCharsets.UTF_8);
      for (String field : ENGINE_OUTPUTS) {
        if (source.contains(field)) {
          (name.equals(TUTOR_PROMPT_CONTEXT) ? promptContextOnly : offenders)
              .add(name + " declares " + field);
        }
      }
    }
    assertThat(offenders)
        .as("a request body may describe what the learner did, never what it is worth")
        .isEmpty();
    // Pinned, not ignored: if this list grows, the exception above stops being one case somebody
    // reasoned about and starts being a pattern.
    assertThat(promptContextOnly)
        .containsExactly("TutorExplainRequest.java declares masteryStatus");
  }

  @Test
  void theTutorPathThatAcceptsAMasteryStatusCannotWriteOne() throws IOException {
    // Whatever a learner claims in a tutor request, the deterministic core stays authoritative:
    // this path reaches no evidence writer, no mastery service, and no repository.
    for (String source : List.of(readSource("ai/TutorService.java"),
        readSource("ai/TutorController.java"))) {
      assertThat(source)
          .doesNotContain("EvidenceService")
          .doesNotContain("EvidenceRepository")
          .doesNotContain("MasteryService")
          .doesNotContain("MasteryRepository");
    }
  }

  @Test
  void theDiagnosticSubmissionCarriesOnlyWhatTheLearnerChose() throws IOException {
    String source = readSource("assessment/DiagnosticSubmissionRequest.java");

    // The entire learner-supplied vocabulary for a scored submission: which item, which options.
    assertThat(source).contains("String itemId").contains("List<@NotBlank String> selectedOptions");
    assertThat(source).doesNotContain("correct").doesNotContain("score").doesNotContain("coverage");
  }

  @Test
  void masteryAndRecommendationRoutesAreReadOnly() throws IOException {
    // A write route here would be a route that sets mastery. Both controllers expose GET only.
    for (String controller : List.of(
        "mastery/MasteryMapController.java", "recommendation/RecommendationController.java")) {
      String source = readSource(controller);
      assertThat(source).as(controller).contains("@GetMapping");
      assertThat(source).as(controller)
          .doesNotContain("@PostMapping")
          .doesNotContain("@PutMapping")
          .doesNotContain("@PatchMapping")
          .doesNotContain("@DeleteMapping");
    }
  }

  @Test
  void coverageIsBoundToEvidenceOnlyWhereEvidenceIsWritten() throws IOException {
    // EvidenceCoverage must not be constructible from the delivery layer: the only places that may
    // decide what an observation covered are the repository that reads it back and the assessment
    // path that derives it from persisted responses.
    List<String> constructors = new ArrayList<>();
    for (Path file : mainSources()) {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      if (source.contains("new EvidenceCoverage(")) {
        constructors.add(file.getParent().getFileName() + "/" + file.getFileName());
      }
    }
    assertThat(constructors).containsExactlyInAnyOrder(
        "evidence/EvidenceCoverage.java",
        "evidence/EvidenceRepository.java",
        "assessment/AssessmentRepository.java");
  }

  private static String readSource(String relativePath) throws IOException {
    return Files.readString(sourceRoot().resolve("io/ramals/learningplatform").resolve(relativePath),
        StandardCharsets.UTF_8);
  }

  private static List<Path> mainSources() throws IOException {
    try (Stream<Path> files = Files.walk(sourceRoot())) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  private static Path sourceRoot() {
    Path root = Path.of("src", "main", "java");
    return Files.isDirectory(root) ? root : Path.of("learning-platform", "src", "main", "java");
  }
}
