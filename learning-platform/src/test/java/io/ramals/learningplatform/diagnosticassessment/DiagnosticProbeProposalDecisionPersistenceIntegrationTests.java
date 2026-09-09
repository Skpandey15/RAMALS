package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V060 against real PostgreSQL: a decision row is written with its correlation identity, the table
 * is append-only, and re-recording the same proposal under the same policy version collapses to one
 * row (M2-ADR-032 19; step 2). No mastery, evidence, progression, or DIAGNOSTIC_SELECTION state is
 * touched anywhere in this class.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class DiagnosticProbeProposalDecisionPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static String databaseUrl;
  private static UUID learnerId;

  private JdbcDiagnosticProbeProposalDecisionRepository repository;
  private JdbcTemplate runtimeJdbc;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    String adminUser = requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection =
            DriverManager.getConnection(
                databaseUrl, adminUser, requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      String quotedAdmin = statement.enquoteIdentifier(adminUser, true);
      statement.execute(
          """
          DO $$
          BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_migration') THEN
              CREATE ROLE ramals_core_migration LOGIN PASSWORD 'm0-t05-migration-test';
            ELSE
              ALTER ROLE ramals_core_migration WITH LOGIN PASSWORD 'm0-t05-migration-test';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_runtime') THEN
              CREATE ROLE ramals_core_runtime LOGIN PASSWORD 'm0-t05-runtime-test';
            ELSE
              ALTER ROLE ramals_core_runtime WITH LOGIN PASSWORD 'm0-t05-runtime-test';
            END IF;
          END
          $$;
          """);
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + quotedAdmin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute(
          "GRANT CONNECT ON DATABASE " + quotedDatabase + " TO " + MIGRATION_USER + ", " + RUNTIME_USER);
    }

    Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();

    learnerId = UUID.fromString("01900000-0000-7000-8000-0000000000c1");
    try (Connection connection =
            DriverManager.getConnection(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD);
        Statement statement = connection.createStatement()) {
      // A minimal learner row so the FK resolves. core.learner is the PII-free identity anchor
      // (ADR 0001 / M1-ADR-013): id and the OIDC subject only.
      statement.execute(
          "INSERT INTO core.learner (id, subject) VALUES ('"
              + learnerId
              + "', 'probe-decision-it-subject') ON CONFLICT DO NOTHING");
    }
  }

  private JdbcTemplate jdbc(String user, String password) {
    return new JdbcTemplate(new DriverManagerDataSource(databaseUrl, user, password));
  }

  private DiagnosticProbeProposalDecision decision(String proposalId, boolean accepted) {
    return new DiagnosticProbeProposalDecision(
        proposalId,
        "req-it",
        "run-it",
        "int-it",
        "trace-it",
        learnerId,
        "KAFKA",
        DiagnosticProbeProposal.CONTRACT_VERSION,
        DiagnosticProbeProposalGate.POLICY_VERSION,
        accepted,
        accepted ? List.of("ACCEPTED") : List.of("DOMAIN_BINDING_MISMATCH"),
        null,
        UUID.fromString("01900000-0000-7000-8000-0000000000d1"),
        "LEARNING_OBJECTIVE",
        UUID.fromString("01900000-0000-7000-8000-0000000000f1"),
        "COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE",
        null,
        List.of("ev-1", "ev-2"),
        accepted ? List.of("ev-1") : List.of("ev-1"),
        "prompt-probe",
        "p1",
        "route-diag",
        "anthropic",
        "claude",
        "rv1");
  }

  @BeforeEach
  void wireRepository() {
    // No per-test reset: @BeforeAll drops and re-migrates the schema, so the table starts empty,
    // and every method below writes a distinct proposal_id. The runtime role cannot DELETE from a
    // ledger table anyway (V002 grants it SELECT, INSERT only; the append-only trigger enforces the
    // rest), which is the property theTableIsAppendOnly proves.
    runtimeJdbc = jdbc(RUNTIME_USER, RUNTIME_PASSWORD);
    repository = new JdbcDiagnosticProbeProposalDecisionRepository(runtimeJdbc);
  }

  @Test
  void aDecisionIsWrittenWithItsCorrelationIdentity() {
    repository.append(decision("prop-write", true));

    var recorded = repository.findByProposalId("prop-write");
    assertThat(recorded).isPresent();
    assertThat(recorded.get().accepted()).isTrue();
    assertThat(recorded.get().reasonCodes()).containsExactly("ACCEPTED");
    assertThat(recorded.get().interactionId()).isEqualTo("int-it");
    assertThat(recorded.get().policyVersion()).isEqualTo("DIAGNOSTIC_PROBE_PROPOSAL_GATE_V1");

    Long correlated =
        runtimeJdbc.queryForObject(
            """
            SELECT count(*) FROM ledger.diagnostic_probe_proposal_decision
             WHERE proposal_id = 'prop-write' AND interaction_id = 'int-it'
               AND trace_id = 'trace-it' AND learner_id = ?
            """,
            Long.class,
            learnerId);
    assertThat(correlated).isEqualTo(1L);
  }

  @Test
  void reRecordingTheSameProposalUnderTheSamePolicyCollapsesToOneRow() {
    repository.append(decision("prop-idem", false));
    repository.append(decision("prop-idem", false));

    Long rows =
        runtimeJdbc.queryForObject(
            "SELECT count(*) FROM ledger.diagnostic_probe_proposal_decision WHERE proposal_id = 'prop-idem'",
            Long.class);
    assertThat(rows).isEqualTo(1L);
  }

  @Test
  void theTableIsAppendOnly() {
    repository.append(decision("prop-immutable", true));

    assertThatThrownBy(
            () ->
                runtimeJdbc.update(
                    "UPDATE ledger.diagnostic_probe_proposal_decision SET accepted = false "
                        + "WHERE proposal_id = 'prop-immutable'"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                runtimeJdbc.update(
                    "DELETE FROM ledger.diagnostic_probe_proposal_decision "
                        + "WHERE proposal_id = 'prop-immutable'"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void theAcceptedFlagMustAgreeWithTheReasonCodes() {
    // accepted = true but reasons != ["ACCEPTED"] violates ck_diagnostic_probe_proposal_decision_accepted.
    DiagnosticProbeProposalDecision inconsistent =
        new DiagnosticProbeProposalDecision(
            "prop-bad-flag",
            "req-it",
            "run-it",
            "int-it",
            "trace-it",
            learnerId,
            "KAFKA",
            DiagnosticProbeProposal.CONTRACT_VERSION,
            DiagnosticProbeProposalGate.POLICY_VERSION,
            true,
            List.of("DOMAIN_BINDING_MISMATCH"),
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> repository.append(inconsistent)).isInstanceOf(RuntimeException.class);
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("missing required environment variable " + name);
    }
    return value;
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (var result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }
}
