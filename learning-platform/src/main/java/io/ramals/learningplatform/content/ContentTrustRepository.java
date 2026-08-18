package io.ramals.learningplatform.content;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the trust state of assessment content.
 *
 * <p>There is deliberately no method that sets an arbitrary trust state. The transitions are the
 * ones M1-ADR-006 allows — {@link #promote} and {@link #reject} — and each writes the fields the
 * database constraints require alongside the state, so a caller cannot produce a row that is
 * verified with nobody attached or rejected with no stage named.
 *
 * <p>A general {@code updateTrustState(id, state)} would be more convenient and would be the exact
 * shape needed to promote content by accident.
 */
@Repository
public class ContentTrustRepository {

  private final JdbcTemplate jdbcTemplate;

  public ContentTrustRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<TrustState> trustStateOf(UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT trust_state FROM core.assessment_item_version WHERE id = ?
        """,
        (result, row) -> TrustState.valueOf(result.getString("trust_state")),
        itemVersionId)
        .stream()
        .findFirst();
  }

  /**
   * Moves content to {@code VERIFIED_CONTENT}, naming the reviewer.
   *
   * <p>The {@code WHERE} clause pins the source state. Without it a concurrent rejection could be
   * overwritten by a promotion that read a stale state, and the losing transition would leave no
   * trace — the row would simply be verified.
   */
  public void promote(UUID itemVersionId, String reviewerSubject) {
    jdbcTemplate.update("""
        UPDATE core.assessment_item_version
           SET trust_state = 'VERIFIED_CONTENT',
               verified_by = ?,
               verified_at = CURRENT_TIMESTAMP,
               rejected_at_stage = NULL,
               rejected_reason = NULL
         WHERE id = ? AND trust_state = 'UNVERIFIED'
        """, reviewerSubject, itemVersionId);
  }

  /** Records which stage refused the content, and why. */
  public void reject(UUID itemVersionId, ValidationStage stage, String reason) {
    jdbcTemplate.update("""
        UPDATE core.assessment_item_version
           SET trust_state = 'REJECTED',
               rejected_at_stage = ?,
               rejected_reason = ?,
               verified_by = NULL,
               verified_at = NULL
         WHERE id = ? AND trust_state <> 'VERIFIED_CONTENT'
        """, stage.name(), reason, itemVersionId);
  }

  /** Who approved this content, when it has been approved. */
  public Optional<String> reviewerOf(UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT verified_by FROM core.assessment_item_version WHERE id = ?
        """, (result, row) -> result.getString("verified_by"), itemVersionId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  /** Which stage refused this content, when it has been refused. */
  public Optional<ValidationStage> rejectedAtStage(UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT rejected_at_stage FROM core.assessment_item_version WHERE id = ?
        """, (result, row) -> result.getString("rejected_at_stage"), itemVersionId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .map(ValidationStage::valueOf)
        .findFirst();
  }

  /** Count of content in a given state for one assessment version. Used by the selection tests to
   *  prove unverified rows were present rather than merely absent. */
  public int countInState(UUID assessmentVersionId, TrustState state) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT count(*) FROM core.assessment_item_version
         WHERE assessment_version_id = ? AND trust_state = ?
        """, Integer.class, assessmentVersionId, state.name());
    return count == null ? 0 : count;
  }
}
