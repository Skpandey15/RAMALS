package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V059 (M2-ADR-028): granular diagnostic confidence -- two new, additive tables. Consumes but never
 * modifies V058's evidence model (core.misconception_evidence_observation) or
 * DiagnosticConfidenceCalculatorV1, and touches none of H5's own tables.
 */
class GranularDiagnosticConfidenceMigrationContractTests {

  @Test
  void confidenceObservationAnchorsOnAttemptAndMisconceptionAsTheSnapshotEventIdentity()
      throws IOException {
    assertThat(migration())
        .contains("CREATE TABLE core.misconception_confidence_observation")
        .contains("attempt_id UUID NOT NULL REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT")
        .contains("learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT")
        .contains("misconception_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT")
        .contains("UNIQUE (attempt_id, misconception_id)");
  }

  @Test
  void triggeringObservationIdIsNotRetainedAsTheIdempotencyModel() throws IOException {
    // The Stage 1.2 revision replaced a single triggering-observation reference with
    // UNIQUE(attempt_id, misconception_id) -- there is no single "the" triggering row once
    // recomputation is batched once per submission.
    assertThat(migration()).doesNotContain("triggering_observation_id");
  }

  @Test
  void bandAndPolicyVersionAreCheckConstrained() throws IOException {
    assertThat(migration())
        .contains("ck_misconception_confidence_observation_band")
        .contains("band IN ('INSUFFICIENT_EVIDENCE', 'LOW', 'MODERATE', 'HIGH')")
        .contains("ck_misconception_confidence_observation_policy_version")
        .contains("policy_version = 'DIAGNOSTIC_CONFIDENCE_V1'");
  }

  @Test
  void bandMustMatchTheFrozenCalculatorsOwnDecisionTreeOverItsOwnPersistedCounts() throws IOException {
    // The same SQL-mirror-of-the-decision-tree idiom V056 already established for H5's own table --
    // reused verbatim (case order and thresholds identical) since this is the exact same frozen
    // calculator, not a new one.
    assertThat(migration())
        .contains("ck_misconception_confidence_observation_band_matches_counts")
        .contains("WHEN supporting_count = 0 AND contradictory_count = 0 THEN 'INSUFFICIENT_EVIDENCE'")
        .contains("WHEN contradictory_count = 0 AND supporting_count = 1 THEN 'LOW'")
        .contains("WHEN contradictory_count = 0 AND supporting_count = 2 THEN 'MODERATE'")
        .contains("WHEN contradictory_count = 0 AND supporting_count >= 3 THEN 'HIGH'")
        .contains("WHEN contradictory_count >= 1 AND supporting_count > 3 * contradictory_count THEN 'HIGH'")
        .contains("WHEN contradictory_count >= 1 AND supporting_count - contradictory_count >= 3 THEN 'MODERATE'");
  }

  @Test
  void confidenceObservationIsImmutableOncePersisted() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_misconception_confidence_observation")
        .contains("IF TG_OP IN ('UPDATE', 'DELETE') THEN")
        .contains("misconception confidence observations are immutable")
        .contains("CREATE TRIGGER trg_misconception_confidence_observation_guard")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_confidence_observation");
  }

  @Test
  void confidenceObservationGuardVerifiesLearnerOwnsTheAttemptButNeverReDerivesCountsLive()
      throws IOException {
    assertThat(migration())
        .contains("SELECT learner_id INTO owning_learner_id FROM core.assessment_attempt WHERE id = NEW.attempt_id")
        .contains("does not match the learner who owns attempt")
        // The deliberate historical-snapshot guarantee: no live re-aggregation of
        // core.misconception_evidence_observation inside this guard.
        .doesNotContain("FROM core.misconception_evidence_observation WHERE learner_id = NEW.learner_id");
  }

  @Test
  void provenanceReferencesBothParentTablesOwnExistingPrimaryKeys() throws IOException {
    assertThat(migration())
        .contains("CREATE TABLE core.misconception_confidence_observation_evidence")
        .contains("confidence_observation_id UUID NOT NULL")
        .contains("REFERENCES core.misconception_confidence_observation(id) ON DELETE RESTRICT")
        .contains("evidence_observation_id UUID NOT NULL")
        .contains("REFERENCES core.misconception_evidence_observation(id) ON DELETE RESTRICT")
        .doesNotContain("ALTER TABLE core.misconception_evidence_observation")
        .doesNotContain("ALTER TABLE core.misconception_confidence_observation ");
  }

  @Test
  void provenanceIsImmutableOncePersisted() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_misconception_confidence_observation_evidence")
        .contains("misconception confidence provenance rows are immutable")
        .contains("CREATE TRIGGER trg_misconception_confidence_observation_evidence_guard")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_confidence_observation_evidence");
  }

  @Test
  void provenanceGuardRejectsAnEvidenceObservationFromADifferentLearnerOrMisconception()
      throws IOException {
    assertThat(migration())
        .contains("IF evidence_row.learner_id IS DISTINCT FROM confidence_row.learner_id")
        .contains("OR evidence_row.misconception_id IS DISTINCT FROM confidence_row.misconception_id THEN")
        .contains("does not belong to");
  }

  @Test
  void noExistingMigrationOrTableIsTouchedByThisMigration() throws IOException {
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.misconception_evidence_observation")
        .doesNotContain("ALTER TABLE core.misconception_evidence_observation_mapping")
        .doesNotContain("ALTER TABLE core.diagnostic_confidence_observation")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_provenance")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_relationship")
        .doesNotContain("INSERT INTO core.diagnostic_confidence_observation")
        .doesNotContain("INSERT INTO core.diagnostic_probe_provenance");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V059__granular_diagnostic_confidence.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
