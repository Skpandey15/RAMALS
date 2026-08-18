package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AssessmentCandidateMigrationContractTests {

  private static final Path MIGRATION = Path.of(
      "src/main/resources/db/migration/V018__assessment_candidate_provenance.sql");

  @Test
  void migrationDefinesImmutableUnverifiedCandidateRevision() throws Exception {
    String sql = Files.readString(MIGRATION);
    assertThat(sql)
        .contains("CREATE TABLE core.assessment_candidate_revision")
        .contains("trust_state VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED'")
        .contains("CONSTRAINT ck_candidate_trust CHECK (trust_state = 'UNVERIFIED')")
        .contains("PRIMARY KEY (candidate_id, candidate_revision)")
        .contains("proposal_digest CHAR(64) NOT NULL")
        .contains("CREATE TRIGGER trg_assessment_candidate_revision_immutable")
        .contains("CREATE UNIQUE INDEX uq_candidate_intake_idempotency")
        .contains("model_id_unavailable_reason");
  }
}
