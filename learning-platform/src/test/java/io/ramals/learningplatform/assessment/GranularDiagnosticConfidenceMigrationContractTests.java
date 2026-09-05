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
  void confidenceObservationGuardVerifiesLearnerOwnsTheAttemptButNeverReDerivesCountsFromALiveUnscopedQuery()
      throws IOException {
    assertThat(migration())
        .contains("SELECT learner_id INTO owning_learner_id FROM core.assessment_attempt WHERE id = NEW.attempt_id")
        .contains("does not match the learner who owns attempt")
        // The deliberate historical-snapshot guarantee: the plain BEFORE guard never re-aggregates
        // "every evidence row for this learner and misconception" -- that would be a live, unscoped
        // query, which is what's rejected. The permitted count-consistency check (its own separate,
        // deferred constraint trigger below) is scoped strictly to this row's own provenance set.
        .doesNotContain("FROM core.misconception_evidence_observation WHERE learner_id = NEW.learner_id");
  }

  @Test
  void countsAreVerifiedAgainstTheRowsOwnProvenanceByADeferredConstraintTriggerAtCommit()
      throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.check_misconception_confidence_observation_counts")
        .contains("FROM core.misconception_confidence_observation_evidence e")
        .contains("JOIN core.misconception_evidence_observation o ON o.id = e.evidence_observation_id")
        .contains("WHERE e.confidence_observation_id = NEW.id")
        .contains("count(*) FILTER (WHERE o.outcome = 'SUPPORTING')")
        .contains("count(*) FILTER (WHERE o.outcome = 'CONTRADICTORY')")
        .contains("count(*) FILTER (WHERE o.outcome = 'INCONCLUSIVE')")
        .contains("do not match the aggregated outcomes of its")
        .contains("CREATE CONSTRAINT TRIGGER trg_misconception_confidence_observation_counts_match_provenance")
        .contains("AFTER INSERT ON core.misconception_confidence_observation")
        .contains("DEFERRABLE INITIALLY DEFERRED");
  }

  @Test
  void theDeferredCountCheckIsScopedToTheRowsOwnIdNeverAllEvidenceForTheLearnerAndMisconception()
      throws IOException {
    // The distinction M2-ADR-028 SS6 draws: WHERE e.confidence_observation_id = NEW.id is a
    // self-consistency check on this row's own two halves, never a query keyed by learner_id/
    // misconception_id alone (which would be exactly the rejected live, unscoped re-aggregation).
    assertThat(migration())
        .doesNotContain("WHERE o.learner_id = NEW.learner_id AND o.misconception_id = NEW.misconception_id");
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
