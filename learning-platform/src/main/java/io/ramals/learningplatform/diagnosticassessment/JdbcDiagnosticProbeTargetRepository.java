package io.ramals.learningplatform.diagnosticassessment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only authoritative lookups for {@link DiagnosticProbeProposalGate} against the granular
 * diagnostic ontology (M2-ADR-026, {@code V057}). Reads {@code core.misconception} and
 * {@code core.diagnostic_node} only; writes nothing, and depends on no mastery/evidence/selection
 * type.
 */
@Repository
public class JdbcDiagnosticProbeTargetRepository implements DiagnosticProbeTargetPort {

  private final JdbcTemplate jdbcTemplate;

  public JdbcDiagnosticProbeTargetRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<ResolvedMisconception> findMisconception(UUID misconceptionId) {
    if (misconceptionId == null) {
      return Optional.empty();
    }
    return jdbcTemplate
        .query(
            """
            SELECT id, status, target_objective_id, target_diagnostic_node_id
              FROM core.misconception
             WHERE id = ?
            """,
            (result, row) ->
                new ResolvedMisconception(
                    result.getObject("id", UUID.class),
                    "PUBLISHED".equals(result.getString("status")),
                    result.getObject("target_objective_id", UUID.class),
                    result.getObject("target_diagnostic_node_id", UUID.class)),
            misconceptionId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<DiagnosticProbeProposal.TargetNode.Kind> findDiagnosticNodeKind(UUID nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    return jdbcTemplate
        .query(
            "SELECT node_type FROM core.diagnostic_node WHERE id = ?",
            (result, row) -> mapNodeType(result.getString("node_type")),
            nodeId)
        .stream()
        .flatMap(Optional::stream)
        .findFirst();
  }

  /**
   * {@code core.diagnostic_node.node_type} is {@code CONCEPT} or {@code SUB_CONCEPT}; the proposal's
   * arc kind uses the same two names plus {@code LEARNING_OBJECTIVE} for the objective-targeted
   * case, which is never stored as a node.
   */
  private static Optional<DiagnosticProbeProposal.TargetNode.Kind> mapNodeType(String nodeType) {
    if ("CONCEPT".equals(nodeType)) {
      return Optional.of(DiagnosticProbeProposal.TargetNode.Kind.CONCEPT);
    }
    if ("SUB_CONCEPT".equals(nodeType)) {
      return Optional.of(DiagnosticProbeProposal.TargetNode.Kind.SUB_CONCEPT);
    }
    return Optional.empty();
  }
}
