package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedbackReadModel.RubricResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Subject-scoped, bounded read adapter over immutable M2-T12 evaluation decisions. */
@Repository
public class AssessmentFeedbackRepository {

  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private final JdbcTemplate jdbc;

  public AssessmentFeedbackRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns at most one decision and applies learner ownership in the SQL predicate. */
  public Optional<AssessmentFeedbackReadModel> findLatestForSubject(String subject) {
    return jdbc
        .query(
            """
            SELECT decision.outcome, decision.answer_version, decision.rubric_version,
                   decision.feedback, decision.dimension_results, decision.decided_at
              FROM ledger.assessment_evaluation_decision decision
              JOIN ledger.grounding_retrieval_record grounding
                ON grounding.context_id = decision.context_id
              JOIN core.learner learner ON learner.id = grounding.learner_id
             WHERE learner.subject = ? AND learner.status = 'ACTIVE'
             ORDER BY decision.decided_at DESC, decision.id DESC
             LIMIT 1
            """,
            (result, row) -> map(result),
            subject)
        .stream()
        .findFirst();
  }

  private static AssessmentFeedbackReadModel map(ResultSet result) throws SQLException {
    return new AssessmentFeedbackReadModel(
        result.getString("outcome"),
        result.getString("answer_version"),
        result.getString("rubric_version"),
        result.getString("feedback"),
        rubricResults(result.getString("dimension_results")),
        instant(result, "decided_at"));
  }

  static List<RubricResult> rubricResults(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      StoredRubricResult[] stored = JSON.readValue(raw, StoredRubricResult[].class);
      return Arrays.stream(stored)
          .map(
              result ->
                  new RubricResult(
                      result.dimensionId(),
                      result.score(),
                      result.maxScore(),
                      result.reason()))
          .toList();
    } catch (RuntimeException invalidStoredProjection) {
      // Stored proposal content is untrusted at the learner boundary. Fail closed without logging
      // the raw JSON, which may contain rejected model content or learner data.
      return List.of();
    }
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private record StoredRubricResult(
      String dimensionId,
      java.math.BigDecimal score,
      java.math.BigDecimal maxScore,
      String reason,
      List<String> evidenceIds) {}
}
