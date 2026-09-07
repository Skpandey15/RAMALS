package io.ramals.learningplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V060 records the advisory diagnostic-probe proposal gate's decisions (M2-ADR-032 19): append-only,
 * idempotent, correlated, carrying no prompt or model output, and touching no existing table.
 */
class DiagnosticProbeProposalDecisionMigrationContractTests {

  private static String statements() throws IOException {
    return resource().replaceAll("(?m)--.*$", "");
  }

  private static String resource() throws IOException {
    try (var input =
        DiagnosticProbeProposalDecisionMigrationContractTests.class.getResourceAsStream(
            "/db/migration/V060__diagnostic_probe_proposal_decision.sql")) {
      assertThat(input).as("V060 migration resource").isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }

  @Test
  @DisplayName("the audit table is append-only, idempotent, correlated, and privacy-bounded")
  void auditTableShape() throws IOException {
    String migration = statements();

    assertThat(migration)
        .contains("CREATE TABLE ledger.diagnostic_probe_proposal_decision")
        .contains("interaction_id VARCHAR(64) NOT NULL")
        .contains("trace_id VARCHAR(64),")
        .contains("learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT")
        .contains("policy_version VARCHAR(64) NOT NULL")
        .contains("proposal_contract_version VARCHAR(16) NOT NULL")
        .contains("parser_reason_code VARCHAR(64)")
        .contains("allowed_evidence_refs JSONB NOT NULL")
        .contains("cited_evidence_refs JSONB NOT NULL")
        // Idempotency, the same key ledger.proposal_gate_decision uses.
        .contains("CONSTRAINT uq_diagnostic_probe_proposal_decision UNIQUE (proposal_id, policy_version)")
        .contains("jsonb_array_length(reason_codes) > 0")
        // accepted iff the only reason is ACCEPTED.
        .contains("accepted = (reason_codes = '[\"ACCEPTED\"]'::jsonb)")
        // Append-only via the shared V028 guard.
        .contains("CREATE TRIGGER trg_diagnostic_probe_proposal_decision_append_only")
        .contains("EXECUTE FUNCTION ledger.reject_grounding_audit_mutation()")
        // Correlation and lookup indexes.
        .contains("idx_diagnostic_probe_proposal_decision_interaction")
        .contains("idx_diagnostic_probe_proposal_decision_learner");
  }

  @Test
  @DisplayName("the table has nowhere to put a prompt or model output; no existing table is altered")
  void noPlaintextColumnsAndNoExistingTableTouched() throws IOException {
    String migration = statements();

    // Scoped to the table definition, the V037 way: the COMMENT ON TABLE prose legitimately names
    // "no prompts or model output", so a file-wide assertion would fail on the explanation.
    int start = migration.indexOf("CREATE TABLE ledger.diagnostic_probe_proposal_decision");
    assertThat(start).isNotNegative();
    String tableDefinition = migration.substring(start, migration.indexOf(";", start));
    assertThat(tableDefinition)
        .doesNotContain("prompt_text")
        .doesNotContain("raw_output")
        .doesNotContain("model_output")
        .doesNotContain("thinking")
        .doesNotContain("reasoning")
        .doesNotContain("chain_of_thought")
        .doesNotContain(" TEXT");

    // Contract A / DIAGNOSTIC_SELECTION provenance and the existing gate-decision table are all
    // untouched: V060 only CREATEs.
    assertThat(migration)
        .doesNotContain("ALTER TABLE")
        .doesNotContain("DROP ")
        .doesNotContain("core.diagnostic_probe_relationship")
        .doesNotContain("core.diagnostic_probe_provenance");
  }
}
