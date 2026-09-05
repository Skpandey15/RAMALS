package io.ramals.learningplatform.assessment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): reads and authors
 * {@code core.misconception} rows. Inserted {@code DRAFT}, later {@link #publish}ed;
 * {@code trg_misconception_guard} (V057) makes a published row immutable -- trusted from the
 * database, not Java validation.
 */
@Repository
public class MisconceptionRepository {

  private final JdbcTemplate jdbcTemplate;

  public MisconceptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Inserts a DRAFT misconception targeting a {@code LearningObjective} directly. */
  public void insertTargetingObjective(UUID id, String name, String description, UUID objectiveId) {
    jdbcTemplate.update("""
        INSERT INTO core.misconception
          (id, name, description, target_objective_id, target_diagnostic_node_id)
        VALUES (?, ?, ?, ?, NULL)
        """, id, name, description, objectiveId);
  }

  /** Inserts a DRAFT misconception targeting a {@link DiagnosticNode} (CONCEPT or SUB_CONCEPT). */
  public void insertTargetingNode(UUID id, String name, String description, UUID diagnosticNodeId) {
    jdbcTemplate.update("""
        INSERT INTO core.misconception
          (id, name, description, target_objective_id, target_diagnostic_node_id)
        VALUES (?, ?, ?, NULL, ?)
        """, id, name, description, diagnosticNodeId);
  }

  /** DRAFT -> PUBLISHED. Immutable afterward -- see {@code trg_misconception_guard}. */
  public void publish(UUID id) {
    jdbcTemplate.update("UPDATE core.misconception SET status = 'PUBLISHED' WHERE id = ?", id);
  }

  public Optional<Misconception> findById(UUID id) {
    return jdbcTemplate.query("""
        SELECT id, name, description, target_objective_id, target_diagnostic_node_id
        FROM core.misconception
        WHERE id = ?
        """, MISCONCEPTION_MAPPER, id).stream().findFirst();
  }

  private static final RowMapper<Misconception> MISCONCEPTION_MAPPER = (result, row) -> new Misconception(
      result.getObject("id", UUID.class),
      result.getString("name"),
      result.getString("description"),
      result.getObject("target_objective_id", UUID.class),
      result.getObject("target_diagnostic_node_id", UUID.class));
}
