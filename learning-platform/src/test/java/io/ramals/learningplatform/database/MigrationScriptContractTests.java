package io.ramals.learningplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationScriptContractTests {

  @Test
  void baselineCreatesApprovedSchemasAndRevokesPublicAccess() throws IOException {
    String migration = resource("/db/migration/V001__baseline_schemas.sql");
    assertThat(migration)
        .contains("CREATE SCHEMA IF NOT EXISTS core")
        .contains("CREATE SCHEMA IF NOT EXISTS ledger")
        .contains("CREATE SCHEMA IF NOT EXISTS audit")
        .contains("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
        .contains("REVOKE ALL ON SCHEMA core, ledger, audit FROM PUBLIC");
  }

  @Test
  void runtimeDefaultsAreMutableOnlyInCoreAndAppendOnlyInLedgerAndAudit() throws IOException {
    String migration = resource("/db/migration/V002__roles_and_grants_foundation.sql");
    assertThat(migration)
        .contains("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core")
        .contains("REVOKE ALL ON TABLE core.flyway_schema_history FROM ramals_core_runtime")
        .contains("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA ledger TO ramals_core_runtime")
        .contains("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA audit TO ramals_core_runtime")
        .contains("ON ALL TABLES IN SCHEMA ledger FROM ramals_core_runtime")
        .contains("ON ALL TABLES IN SCHEMA audit FROM ramals_core_runtime")
        .contains("IN SCHEMA ledger\n  GRANT SELECT, INSERT ON TABLES TO ramals_core_runtime")
        .contains("IN SCHEMA audit\n  GRANT SELECT, INSERT ON TABLES TO ramals_core_runtime")
        .doesNotContain("GRANT UPDATE ON ALL TABLES IN SCHEMA ledger")
        .doesNotContain("GRANT DELETE ON ALL TABLES IN SCHEMA ledger")
        .doesNotContain("GRANT UPDATE ON ALL TABLES IN SCHEMA audit")
        .doesNotContain("GRANT DELETE ON ALL TABLES IN SCHEMA audit");
  }

  @Test
  void aiExecutionCommissioningIsAppendOnlyAndPrivacyBounded() throws IOException {
    String migration = resource("/db/migration/V022__ai_execution_commissioning.sql");
    assertThat(migration)
        .contains("CREATE TABLE core.ai_execution_event")
        .contains("UNIQUE (request_id, event_type)")
        .contains("uq_ai_execution_single_terminal")
        .contains("WHERE event_type IN ('SUCCEEDED', 'FAILED')")
        .contains("event_type IN ('STARTED', 'SUCCEEDED', 'FAILED')")
        .contains("GRANT SELECT, INSERT ON TABLE core.ai_execution_event")
        .contains("completed_at >= started_at")
        .doesNotContain("prompt")
        .doesNotContain("learner_context")
        .doesNotContain("raw_output");
  }

  @Test
  void agentCorrelationColumnsAreAnExpandThatARollbackSurvives() throws IOException {
    // Statements only. The migration explains at length why NOT NULL would be wrong here, and an
    // assertion read against the whole file would be satisfied by that explanation -- passing
    // precisely when somebody documents the rule and then breaks it.
    String migration = statements("/db/migration/V024__ai_execution_agent_correlation.sql");
    String alter = migration.substring(
        migration.indexOf("ALTER TABLE core.ai_execution"),
        migration.indexOf(';', migration.indexOf("ALTER TABLE core.ai_execution")));

    assertThat(alter)
        .contains("ADD COLUMN prompt_template_id VARCHAR(64)")
        .contains("ADD COLUMN agent_run_id VARCHAR(64)")
        // The property the whole slice exists for. A rollback restores the previous image against
        // this schema, and that image inserts without these columns: NOT NULL here would make every
        // insert fail at the moment somebody is recovering from a bad release.
        //
        // Scoped to the ALTER rather than the file: the partial index below legitimately reads
        // "WHERE agent_run_id IS NOT NULL", and an assertion over the whole migration would be
        // failing on the index while claiming something about the columns.
        .doesNotContain("NOT NULL")
        // Nor a default. A default is backward compatible, so the generic migration check permits
        // it -- but here it would be a placeholder, and these columns exist to identify something.
        .doesNotContain("DEFAULT");

    // No backfill either, for the same reason: a filled-in value would later be indistinguishable
    // from one that was really recorded.
    assertThat(migration).doesNotContain("UPDATE core.ai_execution");
  }

  @Test
  void agentWorkOutboxIsAtomicIdempotentAndPayloadImmutable() throws IOException {
    String migration = statements("/db/migration/V025__agent_work_transactional_outbox.sql");

    assertThat(migration)
        .contains("CREATE TABLE core.agent_work_outbox")
        .contains("REFERENCES ledger.decision_record(id) ON DELETE RESTRICT")
        .contains("CONSTRAINT uq_agent_work_outbox_request UNIQUE (request_id)")
        .contains("CONSTRAINT uq_agent_work_outbox_source UNIQUE")
        .contains("payload JSONB NOT NULL")
        .contains("payload->>'sourceDecisionId' = source_decision_id::text")
        .contains("CREATE TRIGGER trg_agent_work_identity_immutable")
        .contains("status IN ('PENDING', 'CLAIMED', 'RETRY', 'COMPLETED', 'TERMINAL')")
        .doesNotContain("Kafka")
        .doesNotContain("ramals_ai_runtime");
  }

  @Test
  void groundingAuditIsBoundedAppendOnlyAndCarriesStableReasons() throws IOException {
    String migration = statements("/db/migration/V028__grounding_retrieval_and_gate_audit.sql");

    assertThat(migration)
        .contains("CREATE TABLE ledger.grounding_retrieval_record")
        .contains("source_count BETWEEN 1 AND 64")
        .contains("jsonb_array_length(source_refs) = source_count")
        .contains("CREATE TABLE ledger.proposal_gate_decision")
        .contains("UNIQUE (proposal_id, policy_version)")
        .contains("jsonb_array_length(reason_codes) > 0")
        .contains("CREATE TRIGGER trg_grounding_retrieval_append_only")
        .contains("CREATE TRIGGER trg_proposal_gate_decision_append_only")
        .doesNotContain("raw_prompt")
        .doesNotContain("hidden_reasoning");
  }

  @Test
  void assessmentEvaluationDecisionsAreTraceableBoundedAndReplaySafe() throws IOException {
    String migration =
        statements("/db/migration/V031__assessment_evaluation_decision.sql");

    assertThat(migration)
        .contains("CREATE TABLE ledger.assessment_evaluation_decision")
        .contains("REFERENCES core.ai_execution(id) ON DELETE RESTRICT")
        .contains("REFERENCES ledger.grounding_retrieval_record(context_id) ON DELETE RESTRICT")
        .contains("CONSTRAINT uq_assessment_evaluation_request UNIQUE (request_id)")
        .contains("trace_id VARCHAR(64)")
        .doesNotContain("trace_id VARCHAR(64) NOT NULL")
        .contains("trace_id IS NULL OR length(btrim(trace_id)) BETWEEN 1 AND 64")
        .contains("outcome IN ('ACCEPTED', 'REJECTED', 'MANUAL_REVIEW')")
        .contains("jsonb_array_length(dimension_results) <= 32")
        .contains("decision_digest ~ '^[0-9a-f]{64}$'")
        .contains("CREATE TRIGGER trg_assessment_evaluation_decision_append_only")
        .doesNotContain("raw_prompt")
        .doesNotContain("hidden_reasoning")
        .doesNotContain("answer_text");
  }

  @Test
  void assessmentFeedbackReadHasAnOwnedContextLatestDecisionIndex() throws IOException {
    String migration =
        statements("/db/migration/V032__assessment_feedback_read_index.sql");

    assertThat(migration)
        .contains("CREATE INDEX idx_assessment_evaluation_context_decided")
        .contains("(context_id, decided_at DESC, id DESC)");
  }

  @Test
  void controlledOrchestrationIsBoundedCorrelatedAndDeterministicallyTerminal() throws IOException {
    String migration = statements("/db/migration/V033__controlled_orchestration.sql");

    assertThat(migration)
        .contains("CREATE TABLE ledger.learning_workflow_run")
        .contains("CREATE TABLE ledger.learning_workflow_step")
        // A duplicate trigger must collapse rather than fan out (G05).
        .contains("CONSTRAINT uq_learning_workflow_trigger UNIQUE (trigger_key)")
        .contains("CONSTRAINT uq_learning_workflow_evaluation UNIQUE (evaluation_request_id)")
        // One row per step per run is what bounds the composition structurally (G06).
        .contains("CONSTRAINT uq_learning_workflow_step_name UNIQUE (run_id, step_name)")
        .contains("CHECK (step_index BETWEEN 0 AND 3)")
        .contains("CHECK (attempt_count BETWEEN 0 AND 32)")
        // Every terminal state names its reason, so a stop is never a silent absence (G02, G03).
        .contains("CONSTRAINT ck_learning_workflow_terminal")
        .contains("'RUNNING', 'COMPLETED', 'STOPPED', 'CANCELLED', 'TIMED_OUT', 'FAILED'")
        // Correlation to the execution ledger and the outbox (G08).
        .contains("idx_learning_workflow_step_request")
        .doesNotContain("raw_prompt")
        .doesNotContain("hidden_reasoning")
        .doesNotContain("checkpoint");
  }

  /** One migration with line comments removed, so an assertion reads DDL rather than prose. */
  private String statements(String path) throws IOException {
    return resource(path).replaceAll("(?m)--.*$", "");
  }

  private String resource(String path) throws IOException {
    try (var input = getClass().getResourceAsStream(path)) {
      assertThat(input).as("migration resource %s", path).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
