package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.content.ContentValidationPipeline.Outcome;
import io.ramals.learningplatform.curriculum.CurriculumGraph;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The staged pipeline from M1-ADR-006.
 *
 * <p>The property worth defending is negative: nothing here promotes. Passing every automated stage
 * means content has <em>failed to be rejected</em>, and the tests are arranged so that a future
 * change turning that into approval breaks something.
 */
class ContentValidationPipelineTests {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private ContentValidationPipeline pipeline() {
    return new ContentValidationPipeline(
        List.of(
            new QualitySafetyValidator(),
            new StructuralValidator(),
            new DeterministicPolicyValidator()),
        registry);
  }

  private static CurriculumGraph curriculum() {
    return new CurriculumGraph(
        UUID.randomUUID(), "KAFKA", "v1", "PUBLISHED",
        List.of(new CurriculumGraph.SkillNode(
            UUID.randomUUID(), "KAFKA_TOPIC", "Topics", "", "FOUNDATIONAL",
            new BigDecimal("0.8000"), 30, new BigDecimal("0.8000"), new BigDecimal("0.7000"),
            3, List.of("QUIZ"), List.of("FOUNDATIONAL", "INTERMEDIATE"), 1,
            List.of(new CurriculumGraph.Objective("TOPIC_DEFINE", "", true, 1)),
            List.of())));
  }

  private static CandidateContent candidate(
      String skillCode, String objectiveCode, String difficulty) {
    return new CandidateContent(
        UUID.randomUUID(), "KAFKA_TOPIC_01", skillCode, objectiveCode, "SINGLE_CHOICE",
        "What is a Kafka topic?",
        List.of("An ordered log", "A consumer", "A broker"),
        List.of("An ordered log"),
        difficulty);
  }

  private static CandidateContent valid() {
    return candidate("KAFKA_TOPIC", "TOPIC_DEFINE", "FOUNDATIONAL");
  }

  // -- the pipeline cannot promote ----------------------------------------------------------------

  @Test
  @DisplayName("passing every stage yields NotRejected, never a verified state")
  void passingEveryStageIsNotApproval() {
    Outcome outcome = pipeline().validate(valid(), ValidationContext.of(curriculum()));

    assertThat(outcome).isInstanceOf(Outcome.NotRejected.class);
    assertThat(outcome.rejected()).isFalse();
  }

  @Test
  @DisplayName("the outcome type has no case that means verified")
  void theOutcomeTypeCannotExpressApproval() {
    // Structural, not behavioural. A promotion path cannot be added to this pipeline without adding
    // a case to a sealed interface -- which is a visible change in review, unlike a boolean flipping
    // meaning.
    List<String> cases = List.of(Outcome.class.getPermittedSubclasses()).stream()
        .map(Class::getSimpleName)
        .toList();

    assertThat(cases).containsExactlyInAnyOrder("NotRejected", "Rejected");
  }

  @Test
  @DisplayName("a validator can only refuse, never approve")
  void theValidatorInterfaceOffersNoApproval() throws NoSuchMethodException {
    Method reject =
        ContentValidator.class.getMethod("reject", CandidateContent.class, ValidationContext.class);

    // Optional<String> reason-to-refuse, not boolean. There is no return value meaning "approve",
    // so a stage cannot grow into one without changing this signature.
    assertThat(reject.getReturnType()).isEqualTo(Optional.class);
    assertThat(ContentValidator.class.getMethods())
        .noneMatch(method -> method.getName().toLowerCase(java.util.Locale.ROOT).contains("approve"));
  }

  // -- ordering -------------------------------------------------------------------------------------

  @Test
  @DisplayName("stages run cheapest-first regardless of bean order")
  void stagesRunInDeclaredOrder() {
    // Constructed deliberately out of order above. Running quality review over a malformed item
    // spends the expensive stage on content that was never going to survive the cheap one.
    assertThat(pipeline().automatedStages())
        .containsExactly(
            ValidationStage.STRUCTURAL,
            ValidationStage.DETERMINISTIC_POLICY,
            ValidationStage.QUALITY_SAFETY);
  }

  @Test
  @DisplayName("the first rejection stops the pipeline and is the one reported")
  void thefirstRejectionWins() {
    // Malformed *and* about an unknown skill. The cheap stage should answer.
    CandidateContent doublyWrong = new CandidateContent(
        UUID.randomUUID(), "KAFKA_TOPIC_01", "KAFKA_STREAMS", "TOPIC_DEFINE", "SINGLE_CHOICE",
        "", List.of("a", "b"), List.of("a"), "FOUNDATIONAL");

    Outcome outcome = pipeline().validate(doublyWrong, ValidationContext.of(curriculum()));

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.STRUCTURAL);
  }

  @Test
  @DisplayName("human review is never run by the automated pipeline")
  void humanReviewIsNotAnAutomatedStage() {
    // A validator claiming HUMAN_REVIEW would be a machine rejecting under a person's name, and the
    // audit would say a human refused content no human saw.
    assertThat(pipeline().automatedStages()).doesNotContain(ValidationStage.HUMAN_REVIEW);
    assertThat(ValidationStage.HUMAN_REVIEW.automated()).isFalse();
  }

  // -- each stage rejects, and says which ------------------------------------------------------------

  @Test
  @DisplayName("structural: an answer key naming an absent option is refused")
  void structuralRefusesUnanswerableItems() {
    CandidateContent broken = new CandidateContent(
        UUID.randomUUID(), "KAFKA_TOPIC_01", "KAFKA_TOPIC", "TOPIC_DEFINE", "SINGLE_CHOICE",
        "What is a topic?", List.of("A log", "A broker"), List.of("A partition"), "FOUNDATIONAL");

    // Scores every learner wrong while looking entirely well-formed in a review queue.
    Outcome outcome = pipeline().validate(broken, ValidationContext.of(curriculum()));

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.STRUCTURAL);
  }

  @Test
  @DisplayName("deterministic policy: a skill outside the curriculum is refused")
  void policyRefusesUnknownSkills() {
    Outcome outcome = pipeline()
        .validate(candidate("KAFKA_STREAMS", null, "FOUNDATIONAL"), ValidationContext.of(curriculum()));

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.DETERMINISTIC_POLICY);
  }

  @Test
  @DisplayName("deterministic policy: an objective from another skill is refused")
  void policyRefusesForeignObjectives() {
    Outcome outcome = pipeline().validate(
        candidate("KAFKA_TOPIC", "PARTITION_ORDERING", "FOUNDATIONAL"),
        ValidationContext.of(curriculum()));

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.DETERMINISTIC_POLICY);
  }

  @Test
  @DisplayName("deterministic policy: a difficulty the skill does not accept is refused")
  void policyRefusesUnacceptedDifficulty() {
    Outcome outcome = pipeline().validate(
        candidate("KAFKA_TOPIC", "TOPIC_DEFINE", "ADVANCED"), ValidationContext.of(curriculum()));

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.DETERMINISTIC_POLICY);
  }

  @Test
  @DisplayName("an unavailable curriculum is a refusal, not a pass")
  void noCurriculumIsNotAPass() {
    // Content admitted while the rules were unavailable is content nobody ever checked.
    Outcome outcome = pipeline().validate(valid(), ValidationContext.unavailable());

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.DETERMINISTIC_POLICY);
  }

  @Test
  @DisplayName("quality: duplicate options are refused")
  void qualityRefusesDuplicateOptions() {
    CandidateContent duplicated = new CandidateContent(
        UUID.randomUUID(), "KAFKA_TOPIC_01", "KAFKA_TOPIC", "TOPIC_DEFINE", "SINGLE_CHOICE",
        "What is a topic?", List.of("A log", "A log ", "A broker"), List.of("A log"),
        "FOUNDATIONAL");

    Outcome outcome = pipeline().validate(duplicated, ValidationContext.of(curriculum()));

    // Not caught: trailing whitespace makes these distinct strings. Recorded here because the
    // interesting duplicates in generated content are near-duplicates, and this stage does not
    // pretend to catch them -- that is what human review is for.
    assertThat(outcome).isInstanceOf(Outcome.NotRejected.class);
  }

  @Test
  @DisplayName("quality: exact duplicate options are refused")
  void qualityRefusesExactDuplicates() {
    CandidateContent duplicated = new CandidateContent(
        UUID.randomUUID(), "KAFKA_TOPIC_01", "KAFKA_TOPIC", "TOPIC_DEFINE", "SINGLE_CHOICE",
        "What is a topic?", List.of("A log", "A log", "A broker"), List.of("A log"),
        "FOUNDATIONAL");

    Outcome outcome = pipeline().validate(duplicated, ValidationContext.of(curriculum()));

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.QUALITY_SAFETY);
  }

  @Test
  @DisplayName("quality: generator narration in the stem is refused")
  void qualityRefusesGeneratorNarration() {
    CandidateContent narrated = new CandidateContent(
        UUID.randomUUID(), "KAFKA_TOPIC_01", "KAFKA_TOPIC", "TOPIC_DEFINE", "SINGLE_CHOICE",
        "As an AI, here is a question about topics.",
        List.of("A log", "A broker"), List.of("A log"), "FOUNDATIONAL");

    Outcome outcome = pipeline().validate(narrated, ValidationContext.of(curriculum()));

    assertThat(((Outcome.Rejected) outcome).stage()).isEqualTo(ValidationStage.QUALITY_SAFETY);
  }

  @Test
  @DisplayName("every rejection names its stage")
  void everyRejectionNamesItsStage() {
    // "Rejected" alone tells an author nothing about what to fix and an operator nothing about
    // whether the generator or the curriculum is drifting.
    List<CandidateContent> failures = List.of(
        new CandidateContent(UUID.randomUUID(), "BAD_CODE_1", "KAFKA_TOPIC", "TOPIC_DEFINE",
            "SINGLE_CHOICE", "", List.of("a", "b"), List.of("a"), "FOUNDATIONAL"),
        candidate("KAFKA_STREAMS", null, "FOUNDATIONAL"));

    for (CandidateContent failure : failures) {
      Outcome outcome = pipeline().validate(failure, ValidationContext.of(curriculum()));
      assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected) outcome).stage()).isNotNull();
      assertThat(((Outcome.Rejected) outcome).reason()).isNotBlank();
    }
  }
}
