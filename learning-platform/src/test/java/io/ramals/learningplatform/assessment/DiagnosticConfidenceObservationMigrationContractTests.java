package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V056: one new, additive table for {@link DiagnosticConfidenceCalculatorV1}'s persisted
 * observations. Its hypothesis identity (learner/source objective/target objective/relationship
 * type) is enforced against the already-merged {@code core.diagnostic_probe_provenance} (V055) by
 * a single guard-trigger lookup rather than composite foreign keys, since expressing it as FKs
 * would require adding new unique keys to that already-merged table -- an added CHECK-or-UNIQUE the
 * previous release's image could not roll back against, per
 * {@code scripts/ci/check-migration-compatibility.py}. No existing migration file is edited; V055
 * and V054 keep their own content untouched.
 */
class DiagnosticConfidenceObservationMigrationContractTests {

  @Test
  void theTableReferencesTheHypothesisTupleAndItsTriggeringProvenanceRow() throws IOException {
    assertThat(migration())
        .contains("CREATE TABLE core.diagnostic_confidence_observation")
        .contains("learner_id UUID NOT NULL REFERENCES core.learner(id)")
        .contains("source_objective_id UUID NOT NULL REFERENCES core.learning_objective(id)")
        .contains("target_objective_id UUID NOT NULL REFERENCES core.learning_objective(id)")
        .contains("triggering_provenance_id UUID NOT NULL UNIQUE")
        .contains("REFERENCES core.diagnostic_probe_provenance(id)");
  }

  @Test
  void relationshipTypeAdmitsAllFourValuesTheSameAsV055() throws IOException {
    assertThat(migration())
        .contains("ck_diagnostic_confidence_observation_type")
        .contains("'SAME_OBJECTIVE_CONFIRMATION', 'PREREQUISITE_VALIDATION', 'ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'");
  }

  @Test
  void bandAdmitsExactlyTheFourDiagnosticConfidenceBandValues() throws IOException {
    assertThat(migration())
        .contains("ck_diagnostic_confidence_observation_band")
        .contains("'INSUFFICIENT_EVIDENCE', 'LOW', 'MODERATE', 'HIGH'");
  }

  @Test
  void countsAreConstrainedNonNegative() throws IOException {
    assertThat(migration())
        .contains("ck_diagnostic_confidence_observation_counts_non_negative")
        .contains("supporting_count >= 0 AND contradictory_count >= 0 AND inconclusive_count >= 0");
  }

  @Test
  void policyVersionIsPinnedToTheOneFrozenIdentifier() throws IOException {
    assertThat(migration())
        .contains("ck_diagnostic_confidence_observation_policy_version")
        .contains("policy_version = 'DIAGNOSTIC_CONFIDENCE_V1'");
  }

  @Test
  void observationsAreAppendOnly() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_diagnostic_confidence_observation")
        .contains("diagnostic confidence observations are immutable")
        .contains("CREATE TRIGGER trg_diagnostic_confidence_observation_guard")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_confidence_observation");
  }

  // -------------------------------------------------------------------------------------------
  // Hardening: an observation's own hypothesis identity cannot diverge from the
  // triggering_provenance_id row it claims to have been computed from, and cannot claim a band the
  // frozen DIAGNOSTIC_CONFIDENCE_V1 rule would not have produced from its own persisted counts.
  // -------------------------------------------------------------------------------------------

  @Test
  void theGuardTriggerLooksUpTheFullTriggeringProvenanceRowAndItsOwningAttempt()
      throws IOException {
    assertThat(migration())
        .contains("SELECT * INTO provenance_row")
        .contains("FROM core.diagnostic_probe_provenance")
        .contains("SELECT a.learner_id INTO owning_learner_id")
        .contains("FROM core.assessment_attempt a WHERE a.id = provenance_row.attempt_id");
  }

  @Test
  void theGuardTriggerCompletesAllFourHypothesisIdentityFactsAgainstThatRow() throws IOException {
    assertThat(migration())
        .contains("owning_learner_id IS DISTINCT FROM NEW.learner_id")
        .contains("provenance_row.source_objective_id IS DISTINCT FROM NEW.source_objective_id")
        .contains("provenance_row.target_objective_id IS DISTINCT FROM NEW.target_objective_id")
        .contains("provenance_row.relationship_type IS DISTINCT FROM NEW.relationship_type")
        .contains("hypothesis identity does not match its");
  }

  @Test
  void aNonexistentTriggeringProvenanceIdIsRejectedBeforeTheIdentityComparison() throws IOException {
    assertThat(migration())
        .contains("IF NOT FOUND THEN")
        .contains("does not reference an existing diagnostic_probe_provenance row");
  }

  @Test
  void bandMustMatchWhatTheFrozenPolicyWouldProduceFromThePersistedCounts() throws IOException {
    assertThat(migration())
        .contains("ck_diagnostic_confidence_observation_band_matches_counts")
        .contains("WHEN supporting_count = 0 AND contradictory_count = 0 THEN 'INSUFFICIENT_EVIDENCE'")
        .contains("WHEN contradictory_count = 0 AND supporting_count = 1 THEN 'LOW'")
        .contains("WHEN contradictory_count = 0 AND supporting_count = 2 THEN 'MODERATE'")
        .contains("WHEN contradictory_count = 0 AND supporting_count >= 3 THEN 'HIGH'")
        .contains("WHEN contradictory_count >= 1 AND supporting_count > 3 * contradictory_count THEN 'HIGH'")
        .contains("WHEN contradictory_count >= 1 AND supporting_count - contradictory_count >= 3 THEN 'MODERATE'");
  }

  @Test
  void v055sAndV054sTablesAreNotAlteredByThisMigration() throws IOException {
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.diagnostic_probe_provenance")
        .doesNotContain("INSERT INTO core.diagnostic_probe_provenance")
        .doesNotContain("UPDATE core.diagnostic_probe_provenance")
        .doesNotContain("DELETE FROM core.diagnostic_probe_provenance")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_relationship")
        .doesNotContain("INSERT INTO core.diagnostic_probe_relationship");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V056__diagnostic_confidence_observation.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
