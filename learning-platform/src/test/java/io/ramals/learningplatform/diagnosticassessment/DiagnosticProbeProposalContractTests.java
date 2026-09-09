package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the M2-ADR-032 wire contract and its Java parser in lockstep. The schema is strict and
 * versioned; a golden payload round-trips through {@link DiagnosticProbeProposal#parse}; and the
 * forbidden-field fixture is refused rather than silently ignored.
 */
class DiagnosticProbeProposalContractTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Path repositoryRoot() {
    Path here = Path.of("").toAbsolutePath();
    return Files.isDirectory(here.resolve("contracts")) ? here : here.getParent();
  }

  private static Path contract() {
    return repositoryRoot().resolve("contracts/mvp2/diagnostic-probe-proposal.v1.schema.json");
  }

  private static Path golden(String name) {
    return repositoryRoot().resolve("contracts/golden/" + name);
  }

  @Test
  @DisplayName("the schema is strict, versioned, and internally resolvable")
  void schemaIsStrictAndVersioned() throws IOException {
    String schema = Files.readString(contract(), StandardCharsets.UTF_8);

    assertThat(schema).contains("https://json-schema.org/draft/2020-12/schema");
    assertThat(schema).contains("\"type\": \"object\"");
    assertThat(schema).contains("\"additionalProperties\": false");
    assertThat(schema).contains("\"contractVersion\": { \"const\": \"1.0\" }");
    assertThat(schema).contains("\"proposalType\": {");
    assertThat(schema).contains("\"const\": \"DIAGNOSTIC_PROBE_CANDIDATE\"");
    // M2-ADR-032 22: no bare confidence / probability / rank field on this proposal type.
    assertThat(schema).doesNotContain("\"confidence\"");
    assertThat(schema).doesNotContain("\"probability\"");

    Matcher references =
        Pattern.compile("\"\\$ref\"\\s*:\\s*\"#/\\$defs/([^\"]+)\"").matcher(schema);
    while (references.find()) {
      assertThat(schema)
          .as("resolves #/$defs/%s", references.group(1))
          .contains("\"%s\": {".formatted(references.group(1)));
    }
  }

  @Test
  @DisplayName("the golden payload round-trips through the Java parser")
  void goldenPayloadParses() throws IOException {
    Map<String, Object> payload =
        JSON.readValue(
            Files.readString(golden("diagnostic-probe-proposal-v1.json"), StandardCharsets.UTF_8),
            new TypeReference<>() {});

    DiagnosticProbeProposal proposal =
        DiagnosticProbeProposal.parse(payload, "prop-3f2a9c10", "req-8b1d4e77", "run-c4e21a90");

    assertThat(proposal.contractVersion()).isEqualTo(DiagnosticProbeProposal.CONTRACT_VERSION);
    assertThat(proposal.domain()).isEqualTo("KAFKA");
    assertThat(proposal.probeIntent())
        .isEqualTo(DiagnosticProbeProposal.ProbeIntent.DISCRIMINATE_BETWEEN_EVIDENCE_STATES);
    assertThat(proposal.evidenceRefs()).containsExactly("mev-11110000", "mev-22220000");
    assertThat(proposal.candidateProbeRef()).isNull();
  }

  @Test
  @DisplayName("a payload carrying a forbidden confidence field is refused, not ignored")
  void forbiddenFieldFixtureIsRefused() throws IOException {
    Map<String, Object> payload =
        JSON.readValue(
            Files.readString(
                golden("diagnostic-probe-proposal-v1-forbidden-confidence.invalid.json"),
                StandardCharsets.UTF_8),
            new TypeReference<>() {});

    assertThatThrownBy(
            () -> DiagnosticProbeProposal.parse(payload, "prop-bad0001", "req-bad0001", "run-bad0001"))
        .isInstanceOfSatisfying(
            DiagnosticProbeProposal.MalformedProbeProposalException.class,
            malformed -> assertThat(malformed.reasonCode()).isEqualTo("PROPOSAL_FORBIDDEN_FIELD"));
  }

  @Test
  @DisplayName("the existing pinned MVP-2 v1 contracts are untouched by this addition")
  void existingContractsAreUnchanged() throws IOException {
    for (String pinned :
        List.of(
            "diagnostic-proposal.v1.schema.json",
            "assessment-evaluation-proposal.v1.schema.json",
            "grounded-context.v1.schema.json",
            "agent-work.v1.schema.json")) {
      Path path = repositoryRoot().resolve("contracts/mvp2/" + pinned);
      assertThat(path).exists();
      String schema = Files.readString(path, StandardCharsets.UTF_8);
      assertThat(schema).contains("\"contractVersion\": { \"const\": \"1.0\" }");
      assertThat(schema).contains("\"additionalProperties\": false");
    }
  }
}
