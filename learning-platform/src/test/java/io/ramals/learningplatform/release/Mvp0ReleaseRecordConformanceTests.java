package io.ramals.learningplatform.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M1-T00: the MVP-0 release record must describe the deterministic control accurately, and the
 * MVP-1 sequencing exception must be visible where the criterion it changes lives.
 *
 * <p>The release record previously listed five of the seven frozen identifiers. Nothing was wrong
 * with the code — {@code MASTERY_STATUS_POLICY_V1} and {@code SESSION_POLICY_V1} were stamped on
 * records like the rest — but a reader checking conformance against the document would have
 * concluded those two were not load-bearing. These assertions keep the document honest as the code
 * moves.
 */
class Mvp0ReleaseRecordConformanceTests {

  /** Every identifier that defines a consequential decision, and must never change in place. */
  private static final List<String> FROZEN_IDENTIFIERS = List.of(
      "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V1", "MASTERY_STATUS_POLICY_V1",
      "RECOMMENDATION_POLICY_V1", "PROGRESSION_POLICY_V1", "DIAGNOSTIC_SCORING_V1",
      "SESSION_POLICY_V1");

  private static final String RELEASE_RECORD = "docs/release/mvp0-release-candidate.md";
  private static final String ADR_000 = "docs/adr/M1-ADR-000-mvp1-engineering-before-r1.md";
  private static final String ADR_010 = "docs/adr/M1-ADR-010-assessment-evaluation-is-formative-only.md";

  @Test
  void releaseRecordListsEveryFrozenIdentifier() throws IOException {
    String record = read(RELEASE_RECORD);
    assertThat(FROZEN_IDENTIFIERS)
        .as("the release record must name every frozen identifier, not a subset")
        .allSatisfy(identifier -> assertThat(record).contains(identifier));
  }

  @Test
  void releaseRecordRecordsTheDeterministicControlCommit() throws IOException {
    // MVP-1 compares against a specific baseline; "the MVP-0 code" is not a reference.
    assertThat(read(RELEASE_RECORD))
        .as("the release record must identify one exact deterministic control commit")
        .containsPattern("(?i)deterministic control.*`v0\\.1\\.0-rc2`");
  }

  @Test
  void releaseRecordCarriesTheMvp1SequencingException() throws IOException {
    String record = read(RELEASE_RECORD);
    assertThat(record)
        .as("the R1 sequencing exception must be visible where the criterion it changes lives")
        .contains("M1-ADR-000");
    assertThat(record)
        .as("R1 must remain explicitly open rather than quietly reclassified")
        .containsIgnoringCase("R1 stays open");
  }

  @Test
  void adoptedMvp1AdrsExistAndAreIndexed() throws IOException {
    assertThat(repositoryRoot().resolve(ADR_000)).as("M1-ADR-000 must be adopted in the repository")
        .exists();
    assertThat(repositoryRoot().resolve(ADR_010)).as("M1-ADR-010 must be adopted before M1-T10")
        .exists();

    String index = read("docs/adr/README.md");
    assertThat(index).as("the ADR index must list M1-ADR-000").contains("M1-ADR-000");
    assertThat(index).as("the ADR index must list M1-ADR-010").contains("M1-ADR-010");
  }

  @Test
  void assessmentEvaluationIsRecordedAsFormativeOnly() throws IOException {
    // The one place an AI output could have touched scored evidence. If this wording weakens, the
    // proposal-only invariant has a hole in it again.
    String adr = read(ADR_010);
    assertThat(adr).contains("FORMATIVE_ONLY");
    assertThat(adr)
        .as("the ADR must state explicitly that AI evaluation cannot create evidence")
        .contains("ledger.evidence");
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
  }

  /** Tests run from the module directory in Gradle and from the root in some IDE setups. */
  private static Path repositoryRoot() {
    Path here = Path.of("").toAbsolutePath();
    return Files.isDirectory(here.resolve("docs")) ? here : here.getParent();
  }
}
