package io.ramals.learningplatform.assessment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): reads and authors
 * {@code core.diagnostic_node} rows. A row is inserted {@code DRAFT} (freely editable) and later
 * {@link #publish}ed; {@code trg_diagnostic_node_guard} (V057) makes a published row immutable and
 * rejects a SUB_CONCEPT whose parent is not itself a CONCEPT -- this class trusts the database to
 * enforce both, not Java validation.
 */
@Repository
public class DiagnosticNodeRepository {

  private final JdbcTemplate jdbcTemplate;

  public DiagnosticNodeRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Inserts a DRAFT CONCEPT -- {@code objectiveId} is the one objective it refines. */
  public void insertConcept(
      UUID id, UUID objectiveId, String name, String description, int displayOrder) {
    jdbcTemplate.update("""
        INSERT INTO core.diagnostic_node
          (id, objective_id, parent_node_id, node_type, name, description, display_order)
        VALUES (?, ?, NULL, 'CONCEPT', ?, ?, ?)
        """, id, objectiveId, name, description, displayOrder);
  }

  /** Inserts a DRAFT SUB_CONCEPT -- {@code parentNodeId} is the one CONCEPT it refines. */
  public void insertSubConcept(
      UUID id, UUID parentNodeId, String name, String description, int displayOrder) {
    jdbcTemplate.update("""
        INSERT INTO core.diagnostic_node
          (id, objective_id, parent_node_id, node_type, name, description, display_order)
        VALUES (?, NULL, ?, 'SUB_CONCEPT', ?, ?, ?)
        """, id, parentNodeId, name, description, displayOrder);
  }

  /** DRAFT -> PUBLISHED. Immutable afterward -- see {@code trg_diagnostic_node_guard}. */
  public void publish(UUID id) {
    jdbcTemplate.update(
        "UPDATE core.diagnostic_node SET status = 'PUBLISHED' WHERE id = ?", id);
  }

  public Optional<DiagnosticNode> findById(UUID id) {
    return jdbcTemplate.query("""
        SELECT id, objective_id, parent_node_id, node_type, name, description, display_order
        FROM core.diagnostic_node
        WHERE id = ?
        """, NODE_MAPPER, id).stream().findFirst();
  }

  private static final RowMapper<DiagnosticNode> NODE_MAPPER = (result, row) -> new DiagnosticNode(
      result.getObject("id", UUID.class),
      result.getObject("objective_id", UUID.class),
      result.getObject("parent_node_id", UUID.class),
      DiagnosticNodeType.valueOf(result.getString("node_type")),
      result.getString("name"),
      result.getString("description"),
      result.getInt("display_order"));
}
