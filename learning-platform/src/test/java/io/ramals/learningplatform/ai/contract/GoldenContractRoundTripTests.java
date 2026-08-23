package io.ramals.learningplatform.ai.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ramals.learningplatform.grounding.GroundedContext;

/**
 * M1-ADR-002: Java records are hand-written and validated against the canonical contract by these
 * fixtures. They are the entire reason it is safe not to generate the Java side.
 *
 * <p>Each fixture is deserialized into the record and re-serialized, and the result must be
 * semantically identical to the file. A field added to the contract without a matching record change
 * is dropped on the way back out and fails here. Weakening these assertions removes the guarantee
 * the ADR relies on.
 */
class GoldenContractRoundTripTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void minimalRequestSurvivesRoundTrip() throws IOException {
    assertRoundTrip("request-tutor-minimal.json", AiRequestEnvelope.class);
  }

  @Test
  void fullRequestSurvivesRoundTrip() throws IOException {
    assertRoundTrip("request-tutor-full.json", AiRequestEnvelope.class);
  }

  @Test
  void crossDomainRequestSurvivesRoundTrip() throws IOException {
    assertRoundTrip("request-tutor-cross-domain.json", AiRequestEnvelope.class);
  }

  @Test
  void crossDomainRequestCarriesADomainTheAgentDidNotAssume() throws IOException {
    // The point of the fixture: a domain that is not KAFKA, with no seed data and no curriculum
    // behind it, must cross the boundary intact. If this ever fails, the platform has acquired an
    // assumption about which domain it serves.
    AiRequestEnvelope envelope =
        MAPPER.readValue(read("request-tutor-cross-domain.json"), AiRequestEnvelope.class);

    assertThat(envelope.domainContext()).isNotNull();
    assertThat(envelope.domainContext().domainCode()).isEqualTo("BTECH_DBMS");
    assertThat(envelope.domainContext().domainType()).isEqualTo(DomainType.ACADEMIC);
    assertThat(envelope.learningGoalContext().goalType()).isEqualTo(GoalType.DEGREE_COMPETENCY);
  }

  @Test
  void crossDomainRequestCarriesIdentifiersTheOldContractRejected() throws IOException {
    // Regression fixture for the 64-vs-96 mismatch: core.skill.stable_code is VARCHAR(96), the
    // boundary capped identifiers at 64, so a legal skill code was storable and unsendable. Both a
    // skillCode and a prerequisite sit in the band that used to fail.
    AiRequestEnvelope envelope =
        MAPPER.readValue(read("request-tutor-cross-domain.json"), AiRequestEnvelope.class);

    assertThat(envelope.learningContext().skillCode().length()).isGreaterThan(64);
    assertThat(envelope.learningContext().skillCode().length()).isLessThanOrEqualTo(96);
    assertThat(envelope.learningContext().prerequisites())
        .anyMatch(prerequisite -> prerequisite.length() > 64);
  }

  @Test
  void tutorProposalSurvivesRoundTrip() throws IOException {
    assertRoundTrip("proposal-tutor.json", AiProposalEnvelope.class);
  }

  @Test
  void formativeEvaluationProposalSurvivesRoundTrip() throws IOException {
    assertRoundTrip("proposal-assessment-evaluate.json", AiProposalEnvelope.class);
  }

  @Test
  void capabilitiesSurviveRoundTrip() throws IOException {
    assertRoundTrip("capabilities.json", AiCapabilities.class);
  }

  @Test
  void groundedContextSurvivesRoundTrip() throws IOException {
    assertRoundTrip("grounded-context-v1.json", GroundedContext.class);
  }

  @Test
  void assessmentEvaluationRequestSurvivesRoundTrip() throws IOException {
    assertRoundTrip("request-assessment-evaluation.json", AssessmentEvaluationRequest.class);
  }

  @Test
  void fullRequestDeserializesToTheExpectedValues() throws IOException {
    AiRequestEnvelope envelope =
        MAPPER.readValue(read("request-tutor-full.json"), AiRequestEnvelope.class);

    assertThat(envelope.contractVersion()).isEqualTo(AiRequestEnvelope.CONTRACT_VERSION);
    assertThat(envelope.constraints().interactionClass()).isEqualTo(InteractionClass.INTERACTIVE_AI);
    assertThat(envelope.constraints().deadlineMs()).isEqualTo(8000);
    assertThat(envelope.learningContext().prerequisites())
        .containsExactly("KAFKA_TOPIC", "KAFKA_BROKER");
    // Fixed-scale string, not a double: 0.7200 must not become 0.72.
    assertThat(envelope.learningContext().masteryScore()).isEqualTo("0.7200");
  }

  @Test
  void evaluationProposalIsFormativeOnly() throws IOException {
    // M1-ADR-010 expressed as a value the Java side can enforce, not only as prose in the contract.
    AiProposalEnvelope proposal =
        MAPPER.readValue(read("proposal-assessment-evaluate.json"), AiProposalEnvelope.class);
    assertThat(proposal.trustLevel()).isEqualTo(TrustLevel.FORMATIVE_ONLY);
  }

  @Test
  void decimalPrecisionIsNotLostThroughSerialization() throws IOException {
    // The failure this guards: a BigDecimal-backed score becoming a float somewhere in the chain and
    // silently changing scale. Mastery reproducibility depends on it not happening.
    AiProposalEnvelope proposal =
        MAPPER.readValue(read("proposal-tutor.json"), AiProposalEnvelope.class);
    assertThat(proposal.confidence()).isEqualTo("0.8200");
    assertThat(proposal.usage().estimatedCostUsd()).isEqualTo("0.014300");

    JsonNode reserialized = MAPPER.readTree(MAPPER.writeValueAsString(proposal));
    assertThat(reserialized.get("confidence").asString()).isEqualTo("0.8200");
    assertThat(reserialized.get("usage").get("estimatedCostUsd").asString()).isEqualTo("0.014300");
  }

  @Test
  void everyGoldenFixtureIsCoveredByATest() throws IOException {
    // Adding a fixture without a round-trip assertion would let the Python side pin a shape the Java
    // side never sees, which is exactly the drift these fixtures exist to prevent.
    List<String> covered = List.of(
        "request-tutor-minimal.json", "request-tutor-full.json",
        "request-tutor-cross-domain.json", "proposal-tutor.json",
        "proposal-assessment-evaluate.json", "capabilities.json",
        "problem-deadline-exceeded.json", "grounded-context-v1.json",
        "request-assessment-evaluation.json");

    try (var entries = Files.list(goldenDirectory())) {
      List<String> present = entries.map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".json")).sorted().toList();
      assertThat(present)
          .as("every golden fixture must be exercised by a round-trip or contract test")
          .containsExactlyInAnyOrderElementsOf(covered);
    }
  }

  private static <T> void assertRoundTrip(String fixture, Class<T> type) throws IOException {
    String original = read(fixture);
    T value = MAPPER.readValue(original, type);
    String reserialized = MAPPER.writeValueAsString(value);

    assertThat(MAPPER.readTree(reserialized))
        .as("%s must round-trip through %s without losing or renaming a field",
            fixture, type.getSimpleName())
        .isEqualTo(MAPPER.readTree(original));
  }

  private static String read(String fixture) throws IOException {
    return Files.readString(goldenDirectory().resolve(fixture), StandardCharsets.UTF_8);
  }

  private static Path goldenDirectory() {
    Path here = Path.of("").toAbsolutePath();
    Path candidate = here.resolve("contracts/golden");
    return Files.isDirectory(candidate) ? candidate : here.getParent().resolve("contracts/golden");
  }

  /** Kept to prove the raw map payload is preserved verbatim, not coerced into a typed shape. */
  @Test
  void openProposalPayloadIsPreservedVerbatim() throws IOException {
    AiProposalEnvelope proposal =
        MAPPER.readValue(read("proposal-tutor.json"), AiProposalEnvelope.class);
    Map<String, Object> payload = proposal.proposal();
    assertThat(payload).containsEntry("responseType", "EXPLAIN_WITH_ANALOGY");
    assertThat(payload.get("checksForUnderstanding")).isInstanceOf(List.class);
  }
}
