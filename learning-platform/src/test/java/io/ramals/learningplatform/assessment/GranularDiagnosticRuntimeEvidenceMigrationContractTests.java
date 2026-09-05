package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V058 (M2-ADR-027): granular diagnostic runtime evidence capture -- two new, additive tables.
 * Consumes but never modifies V057's ontology (core.misconception, core.assessment_item_option_
 * misconception) or MisconceptionEvidenceOutcome, and touches none of H4b/H5's tables.
 */
class GranularDiagnosticRuntimeEvidenceMigrationContractTests {

  @Test
  void observationAnchorsProvenanceOnTheExactAssessmentResponseRow() throws IOException {
    assertThat(migration())
        .contains("CREATE TABLE core.misconception_evidence_observation")
        .contains("response_id UUID NOT NULL REFERENCES core.assessment_response(id) ON DELETE RESTRICT")
        .contains("learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT")
        .contains("misconception_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT")
        .contains("UNIQUE (response_id, misconception_id)");
  }

  @Test
  void observationOutcomeAndPolicyVersionAreCheckConstrained() throws IOException {
    assertThat(migration())
        .contains("ck_misconception_evidence_observation_outcome")
        .contains("outcome IN ('SUPPORTING', 'CONTRADICTORY', 'INCONCLUSIVE')")
        .contains("ck_misconception_evidence_observation_policy_version")
        .contains("policy_version = 'MISCONCEPTION_EVIDENCE_V1'");
  }

  @Test
  void observationIsImmutableOncePersisted() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_misconception_evidence_observation")
        .contains("IF TG_OP IN ('UPDATE', 'DELETE') THEN")
        .contains("misconception evidence observations are immutable")
        .contains("CREATE TRIGGER trg_misconception_evidence_observation_guard")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_evidence_observation");
  }

  @Test
  void observationGuardVerifiesLearnerOwnsTheResponsesOwnAttempt() throws IOException {
    assertThat(migration())
        .contains("SELECT a.learner_id INTO owning_learner_id")
        .contains("FROM core.assessment_attempt a WHERE a.id = response_row.attempt_id")
        .contains("does not match the learner who owns response");
  }

  @Test
  void observationGuardDerivesExpectedOutcomeIndependentlyOfItsOwnProvenanceChildren()
      throws IOException {
    // The blocking correction: the guard must compute eligibility and the expected outcome
    // straight from assessment_item_option_misconception + assessment_response, never by
    // consulting core.misconception_evidence_observation_mapping (which cannot exist yet at the
    // moment this parent row is first written).
    assertThat(migration())
        .contains("SELECT EXISTS (\n"
            + "    SELECT 1 FROM core.assessment_item_option_misconception m\n"
            + "     WHERE m.item_version_id = response_row.item_version_id\n"
            + "       AND m.misconception_id = NEW.misconception_id\n"
            + "       AND m.status = 'PUBLISHED'\n"
            + "       AND m.published_at <= response_row.created_at\n"
            + "  ) INTO any_eligible")
        .contains("has no event-time-eligible PUBLISHED mapping for response")
        .contains("IF response_row.is_correct THEN")
        .contains("expected_outcome := 'CONTRADICTORY'")
        .contains("AND m.option_id = (response_row.response_jsonb -> 'selectedOptions' ->> 0)")
        .contains("expected_outcome := CASE WHEN selected_option_eligible THEN 'SUPPORTING' ELSE 'INCONCLUSIVE' END")
        .contains("IF NEW.outcome <> expected_outcome THEN")
        .contains("does not match the event-time-derived outcome")
        .doesNotContain("FROM core.misconception_evidence_observation_mapping WHERE observation_id");
  }

  @Test
  void observationCreatedAtIsNeverCallerSuppliable() throws IOException {
    // Unlike V057's own published_at COALESCE pattern, this column is unconditionally overwritten.
    assertThat(migration())
        .contains("NEW.created_at := CURRENT_TIMESTAMP;")
        .doesNotContain("NEW.created_at := COALESCE(NEW.created_at");
  }

  @Test
  void provenanceReferencesTheOptionMappingsOwnExistingPrimaryKeyNotANewUniqueKey()
      throws IOException {
    // The same lesson the H5 hardening round and V057 already established: adding a new unique
    // key to an already-merged table to support a composite FK breaks rollback for the previous
    // release's image. This migration must not repeat that mistake against
    // assessment_item_option_misconception.
    assertThat(migration())
        .contains("CREATE TABLE core.misconception_evidence_observation_mapping")
        .contains("FOREIGN KEY (item_version_id, option_id, misconception_id)")
        .contains("REFERENCES core.assessment_item_option_misconception(item_version_id, option_id, misconception_id)")
        .doesNotContain("ALTER TABLE core.assessment_item_option_misconception");
  }

  @Test
  void provenanceIsImmutableOncePersisted() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_misconception_evidence_observation_mapping")
        .contains("misconception evidence provenance rows are immutable")
        .contains("CREATE TRIGGER trg_misconception_evidence_observation_mapping_guard")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_evidence_observation_mapping");
  }

  @Test
  void provenanceGuardRejectsAMisconceptionMismatchAgainstItsOwnObservation() throws IOException {
    assertThat(migration())
        .contains("IF observation_row.misconception_id IS DISTINCT FROM NEW.misconception_id THEN")
        .contains("does not match its observation");
  }

  @Test
  void provenanceGuardRejectsAMappingThatIsNotActuallyPublished() throws IOException {
    assertThat(migration())
        .contains("IF NOT FOUND OR mapping_row.status <> 'PUBLISHED' THEN")
        .contains("is not a PUBLISHED misconception option mapping");
  }

  @Test
  void provenanceGuardRejectsAMappingPublishedAfterTheResponseItCites() throws IOException {
    assertThat(migration())
        .contains("IF mapping_row.published_at > response_row.created_at THEN")
        .contains("cannot support historical evidence");
  }

  @Test
  void noExistingMigrationOrTableIsTouchedByThisMigration() throws IOException {
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.misconception")
        .doesNotContain("ALTER TABLE core.diagnostic_node")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_relationship")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_provenance")
        .doesNotContain("ALTER TABLE core.diagnostic_confidence_observation")
        .doesNotContain("INSERT INTO core.misconception")
        .doesNotContain("INSERT INTO core.diagnostic_probe_relationship")
        .doesNotContain("INSERT INTO core.diagnostic_probe_provenance")
        .doesNotContain("INSERT INTO core.diagnostic_confidence_observation");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V058__granular_diagnostic_runtime_evidence.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
