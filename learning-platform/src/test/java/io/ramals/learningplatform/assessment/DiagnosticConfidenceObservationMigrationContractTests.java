package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V056 (M2-ADR-026): a single new, additive table for
 * {@link DiagnosticConfidenceCalculatorV1}'s persisted observations -- no existing migration is
 * touched.
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
        .contains("BEFORE UPDATE OR DELETE ON core.diagnostic_confidence_observation");
  }

  @Test
  void neitherV055NorV054sTablesAreTouchedByThisMigration() throws IOException {
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.diagnostic_probe_provenance")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_relationship")
        .doesNotContain("INSERT INTO core.diagnostic_probe_provenance")
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
