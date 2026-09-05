package io.ramals.learningplatform.assessment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): reads and authors
 * {@code core.assessment_item_option_misconception} rows. Inserted {@code DRAFT}, later
 * {@link #publish}ed; {@code trg_assessment_item_option_misconception_guard} (V057) enforces the
 * item is {@code SINGLE_CHOICE}, the option genuinely exists and is genuinely incorrect, that a
 * mapping may only publish once its own misconception is already published, and immutability
 * thereafter -- all trusted from the database, not Java validation.
 */
@Repository
public class MisconceptionOptionMappingRepository {

  private final JdbcTemplate jdbcTemplate;

  public MisconceptionOptionMappingRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Inserts a DRAFT mapping tagging {@code optionId} on {@code itemVersionId} as evidence of
   * {@code misconceptionId}. */
  public void insert(UUID itemVersionId, String optionId, UUID misconceptionId) {
    jdbcTemplate.update("""
        INSERT INTO core.assessment_item_option_misconception
          (item_version_id, option_id, misconception_id)
        VALUES (?, ?, ?)
        """, itemVersionId, optionId, misconceptionId);
  }

  /** DRAFT -> PUBLISHED. Immutable afterward -- see
   * {@code trg_assessment_item_option_misconception_guard}. */
  public void publish(UUID itemVersionId, String optionId, UUID misconceptionId) {
    jdbcTemplate.update("""
        UPDATE core.assessment_item_option_misconception SET status = 'PUBLISHED'
        WHERE item_version_id = ? AND option_id = ? AND misconception_id = ?
        """, itemVersionId, optionId, misconceptionId);
  }

  /**
   * Whether {@code itemVersionId} is a misconception-evidence-eligible item for
   * {@code misconceptionId} -- carries at least one {@code PUBLISHED} mapping naming it. Never
   * "is this an H4b probe" -- this is a different, orthogonal eligibility question (see
   * {@link MisconceptionOptionMapping}'s own javadoc).
   */
  public boolean isEvidenceEligible(UUID itemVersionId, UUID misconceptionId) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT count(*) FROM core.assessment_item_option_misconception
        WHERE item_version_id = ? AND misconception_id = ? AND status = 'PUBLISHED'
        """, Integer.class, itemVersionId, misconceptionId);
    return count != null && count > 0;
  }

  /**
   * Whether the specific {@code (itemVersionId, optionId)} pair actually selected is, itself,
   * {@code PUBLISHED}-tagged to {@code misconceptionId} -- the fact that distinguishes
   * {@code SUPPORTING} from {@code INCONCLUSIVE} (a different wrong option, or an untagged one,
   * never counts).
   */
  public boolean isOptionPublishedForMisconception(
      UUID itemVersionId, String optionId, UUID misconceptionId) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT count(*) FROM core.assessment_item_option_misconception
        WHERE item_version_id = ? AND option_id = ? AND misconception_id = ? AND status = 'PUBLISHED'
        """, Integer.class, itemVersionId, optionId, misconceptionId);
    return count != null && count > 0;
  }

  /** The scored response {@link MisconceptionEvidenceService} classifies -- the selected option and
   * whether it was correct. Empty if that item was never answered in that attempt, or is not
   * SINGLE_CHOICE (this table only ever maps SINGLE_CHOICE options -- V1 scope, M2-ADR-026 §5/§9). */
  public Optional<ScoredMisconceptionProbeResponse> scoredResponse(UUID attemptId, UUID itemVersionId) {
    return jdbcTemplate.query("""
        SELECT ar.response_jsonb -> 'selectedOptions' ->> 0 AS selected_option_id, ar.is_correct
        FROM core.assessment_response ar
        JOIN core.assessment_item_version iv ON iv.id = ar.item_version_id
        WHERE ar.attempt_id = ? AND ar.item_version_id = ? AND iv.item_type = 'SINGLE_CHOICE'
        """, (result, row) -> new ScoredMisconceptionProbeResponse(
            result.getString("selected_option_id"), result.getBoolean("is_correct")),
        attemptId, itemVersionId).stream().findFirst();
  }

  /** The raw scoring fact behind one counted SINGLE_CHOICE response -- see {@link #scoredResponse}. */
  public record ScoredMisconceptionProbeResponse(String selectedOptionId, boolean isCorrect) {
  }
}
