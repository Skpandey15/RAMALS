package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.observability.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AssessmentRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AssessmentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /** Resolves the latest published diagnostic for a domain, or empty if none is published. */
  public Optional<ResolvedDiagnostic> findPublishedDiagnostic(String domainCode) {
    return jdbcTemplate.query("""
        SELECT av.id, d.code AS domain_code, a.stable_code AS assessment_code,
               av.version_code, av.status
        FROM core.assessment_version av
        JOIN core.assessment a ON a.id = av.assessment_id
        JOIN core.learning_domain d ON d.id = a.domain_id
        WHERE d.code = ? AND a.assessment_type = 'DIAGNOSTIC' AND av.status = 'PUBLISHED'
        ORDER BY av.published_at DESC
        LIMIT 1
        """, DIAGNOSTIC_MAPPER, domainCode).stream().findFirst();
  }

  public Optional<UUID> findCurriculumVersionId(UUID assessmentVersionId) {
    return jdbcTemplate.query(
        "SELECT curriculum_version_id FROM core.assessment_version WHERE id = ?",
        (result, row) -> result.getObject("curriculum_version_id", UUID.class), assessmentVersionId)
        .stream().findFirst();
  }

  public Optional<ResolvedDiagnostic> findDiagnosticByVersionId(UUID assessmentVersionId) {
    return jdbcTemplate.query("""
        SELECT av.id, d.code AS domain_code, a.stable_code AS assessment_code,
               av.version_code, av.status
        FROM core.assessment_version av
        JOIN core.assessment a ON a.id = av.assessment_id
        JOIN core.learning_domain d ON d.id = a.domain_id
        WHERE av.id = ?
        """, DIAGNOSTIC_MAPPER, assessmentVersionId).stream().findFirst();
  }

  public Optional<AssessmentAttempt> findByIdempotency(
      UUID learnerId, UUID assessmentVersionId, String idempotencyKey) {
    return jdbcTemplate.query(
        ATTEMPT_SELECT + " WHERE learner_id = ? AND assessment_version_id = ? AND idempotency_key = ?",
        ATTEMPT_MAPPER, learnerId, assessmentVersionId, idempotencyKey).stream().findFirst();
  }

  public Optional<AssessmentAttempt> findActiveAttempt(UUID learnerId, UUID assessmentVersionId) {
    return jdbcTemplate.query(
        ATTEMPT_SELECT
            + " WHERE learner_id = ? AND assessment_version_id = ? AND status = 'IN_PROGRESS'",
        ATTEMPT_MAPPER, learnerId, assessmentVersionId).stream().findFirst();
  }

  public Optional<AssessmentAttempt> findAttempt(UUID attemptId) {
    return jdbcTemplate.query(ATTEMPT_SELECT + " WHERE id = ?", ATTEMPT_MAPPER, attemptId)
        .stream().findFirst();
  }

  /** Loads and row-locks an attempt so concurrent submissions serialize on its state transition. */
  public Optional<AssessmentAttempt> findAttemptForUpdate(UUID attemptId) {
    return jdbcTemplate.query(
        ATTEMPT_SELECT + " WHERE id = ? FOR UPDATE", ATTEMPT_MAPPER, attemptId)
        .stream().findFirst();
  }

  /** Server-only: loads items with their answer keys for correctness decisions during submit. */
  public List<AssessmentItemScoringView> findItemScoringViews(UUID assessmentVersionId) {
    return jdbcTemplate.query("""
        SELECT iv.id, s.stable_code AS skill_code, iv.item_type,
               iv.options_jsonb AS options, iv.answer_key_jsonb AS answer_key
        FROM core.assessment_item_version iv
        JOIN core.skill s ON s.id = iv.skill_id
        WHERE iv.assessment_version_id = ?
        """, scoringViewMapper(), assessmentVersionId);
  }

  public void insertResponse(
      UUID attemptId, UUID itemVersionId, String responseJson, boolean correct) {
    jdbcTemplate.update("""
        INSERT INTO core.assessment_response
          (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, ?::jsonb, ?)
        """, UuidV7.generate(), attemptId, itemVersionId, responseJson, correct);
  }

  /** Reads persisted responses for scoring. Deliberately does not read the answer key. */
  public List<ScoredResponse> findScoredResponses(UUID attemptId) {
    return jdbcTemplate.query("""
        SELECT s.stable_code AS skill_code,
               jsonb_array_length(iv.options_jsonb) AS option_count,
               r.is_correct
        FROM core.assessment_response r
        JOIN core.assessment_item_version iv ON iv.id = r.item_version_id
        JOIN core.skill s ON s.id = iv.skill_id
        WHERE r.attempt_id = ?
        ORDER BY s.stable_code, r.item_version_id
        """, (result, row) -> new ScoredResponse(
            result.getString("skill_code"),
            result.getInt("option_count"),
            result.getBoolean("is_correct")), attemptId);
  }

  /** Transitions an in-progress attempt to COMPLETED. Returns true if this call finalized it. */
  public boolean completeAttempt(UUID attemptId) {
    return jdbcTemplate.update("""
        UPDATE core.assessment_attempt SET status = 'COMPLETED'
        WHERE id = ? AND status = 'IN_PROGRESS'
        """, attemptId) == 1;
  }

  /**
   * Inserts a new in-progress attempt. Throws a
   * {@link org.springframework.dao.DuplicateKeyException} if the scoped idempotency key or the
   * one-active-attempt invariant is violated by a concurrent writer.
   */
  public AssessmentAttempt insertAttempt(
      UUID learnerId, UUID assessmentVersionId, String idempotencyKey) {
    UUID id = UuidV7.generate();
    jdbcTemplate.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
        """, id, learnerId, assessmentVersionId, idempotencyKey);
    return findAttempt(id).orElseThrow(
        () -> new IllegalStateException("Attempt insert did not persist a row."));
  }

  /** Loads presentable items for an attempt. Never selects the answer key. */
  public List<DiagnosticItem> findItems(UUID assessmentVersionId) {
    return jdbcTemplate.query("""
        SELECT iv.id, iv.item_code, s.stable_code AS skill_code, iv.item_type, iv.stem,
               iv.options_jsonb AS options, iv.display_order
        FROM core.assessment_item_version iv
        JOIN core.skill s ON s.id = iv.skill_id
        WHERE iv.assessment_version_id = ?
        ORDER BY iv.display_order, iv.item_code
        """, itemMapper(), assessmentVersionId);
  }

  private RowMapper<DiagnosticItem> itemMapper() {
    return (result, row) -> new DiagnosticItem(
        result.getObject("id", UUID.class),
        result.getString("item_code"),
        result.getString("skill_code"),
        result.getString("item_type"),
        result.getString("stem"),
        parseOptions(result.getString("options")),
        result.getInt("display_order"));
  }

  private List<DiagnosticItemOption> parseOptions(String optionsJson) {
    if (optionsJson == null || optionsJson.isBlank()) {
      return List.of();
    }
    return List.of(objectMapper.readValue(optionsJson, DiagnosticItemOption[].class));
  }

  private RowMapper<AssessmentItemScoringView> scoringViewMapper() {
    return (result, row) -> {
      List<String> optionIds = parseOptions(result.getString("options")).stream()
          .map(DiagnosticItemOption::id)
          .toList();
      AnswerKey answerKey = objectMapper.readValue(result.getString("answer_key"), AnswerKey.class);
      List<String> correct = answerKey.correct() == null ? List.of() : answerKey.correct();
      return new AssessmentItemScoringView(
          result.getObject("id", UUID.class),
          result.getString("skill_code"),
          result.getString("item_type"),
          optionIds,
          correct);
    };
  }

  private static final String ATTEMPT_SELECT = """
      SELECT id, learner_id, assessment_version_id, status, idempotency_key, created_at, updated_at
      FROM core.assessment_attempt
      """;

  private static final RowMapper<ResolvedDiagnostic> DIAGNOSTIC_MAPPER = (result, row) ->
      new ResolvedDiagnostic(
          result.getObject("id", UUID.class),
          result.getString("domain_code"),
          result.getString("assessment_code"),
          result.getString("version_code"),
          result.getString("status"));

  private static final RowMapper<AssessmentAttempt> ATTEMPT_MAPPER = (result, row) ->
      new AssessmentAttempt(
          result.getObject("id", UUID.class),
          result.getObject("learner_id", UUID.class),
          result.getObject("assessment_version_id", UUID.class),
          result.getString("status"),
          result.getString("idempotency_key"),
          instant(result, "created_at"),
          instant(result, "updated_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
