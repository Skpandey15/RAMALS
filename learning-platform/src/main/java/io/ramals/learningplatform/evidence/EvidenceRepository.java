package io.ramals.learningplatform.evidence;

import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.observability.UuidV7;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceRepository {

  private final JdbcTemplate jdbcTemplate;

  public EvidenceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Maps each skill exercised by an attempt's persisted responses to its stable skill id. */
  public Map<String, UUID> resolveAttemptSkills(UUID attemptId) {
    Map<String, UUID> byCode = new HashMap<>();
    jdbcTemplate.query("""
        SELECT DISTINCT s.stable_code, s.id
        FROM core.assessment_response r
        JOIN core.assessment_item_version iv ON iv.id = r.item_version_id
        JOIN core.skill s ON s.id = iv.skill_id
        WHERE r.attempt_id = ?
        """, (RowCallbackHandler) result ->
            byCode.put(result.getString("stable_code"), result.getObject("id", UUID.class)),
        attemptId);
    return byCode;
  }

  /**
   * Appends diagnostic evidence, reusing any existing row with the same lineage key. Safe under
   * retry: a repeated logical observation collapses to the original evidence.
   */
  public Evidence appendDiagnosticEvidence(
      UUID learnerId, UUID skillId, UUID sourceAttemptId, UUID sourceAssessmentVersionId,
      String scoringVersion, String lineageKey, BigDecimal observedScore, BigDecimal normalizedScore,
      int itemsAnswered, int itemsCorrect, EvidenceCoverage coverage, String interactionId) {
    appendObservation("DIAGNOSTIC", learnerId, skillId, sourceAttemptId, sourceAssessmentVersionId,
        scoringVersion, lineageKey, observedScore, normalizedScore, itemsAnswered, itemsCorrect,
        coverage, interactionId);
    return requireByLineage(lineageKey);
  }

  /**
   * Appends evidence derived from an accepted M2-T12 rubric evaluation. Idempotent on the lineage
   * key, so a replayed workflow trigger reuses the original row rather than crediting the learner
   * twice for one answer.
   */
  public Evidence appendEvaluationEvidence(
      UUID learnerId, UUID skillId, UUID sourceAttemptId, UUID sourceAssessmentVersionId,
      String scoringVersion, String lineageKey, BigDecimal observedScore, BigDecimal normalizedScore,
      int itemsAnswered, int itemsCorrect, EvidenceCoverage coverage, String interactionId) {
    appendObservation("EVALUATION", learnerId, skillId, sourceAttemptId, sourceAssessmentVersionId,
        scoringVersion, lineageKey, observedScore, normalizedScore, itemsAnswered, itemsCorrect,
        coverage, interactionId);
    return requireByLineage(lineageKey);
  }

  /**
   * The one statement that writes an observation, and therefore the one place coverage is bound to
   * an evidence row. Coverage arrays are set through {@code Connection.createArrayOf} rather than
   * assembled as array literals, so a malformed value fails at the driver instead of becoming a
   * string PostgreSQL happens to parse.
   */
  private void appendObservation(
      String evidenceType, UUID learnerId, UUID skillId, UUID sourceAttemptId,
      UUID sourceAssessmentVersionId, String scoringVersion, String lineageKey,
      BigDecimal observedScore, BigDecimal normalizedScore, int itemsAnswered, int itemsCorrect,
      EvidenceCoverage coverage, String interactionId) {
    EvidenceCoverage covered = coverage == null ? EvidenceCoverage.none() : coverage;
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO ledger.evidence
            (id, learner_id, skill_id, evidence_type, source_type, source_attempt_id,
             source_assessment_version_id, scoring_version, lineage_key, observed_score,
             normalized_score, items_answered, items_correct, covered_objective_ids,
             covered_difficulty_bands, interaction_id)
          VALUES (?, ?, ?, ?, 'ASSESSMENT_ATTEMPT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (lineage_key) DO NOTHING
          """);
      statement.setObject(1, UuidV7.generate());
      statement.setObject(2, learnerId);
      statement.setObject(3, skillId);
      statement.setString(4, evidenceType);
      statement.setObject(5, sourceAttemptId);
      statement.setObject(6, sourceAssessmentVersionId);
      statement.setString(7, scoringVersion);
      statement.setString(8, lineageKey);
      statement.setBigDecimal(9, observedScore);
      statement.setBigDecimal(10, normalizedScore);
      statement.setInt(11, itemsAnswered);
      statement.setInt(12, itemsCorrect);
      statement.setArray(13, connection.createArrayOf("uuid", covered.objectiveIds().toArray()));
      statement.setArray(14, connection.createArrayOf("varchar",
          covered.difficultyBands().stream().map(Enum::name).sorted().toArray()));
      statement.setString(15, interactionId);
      return statement;
    });
  }

  /**
   * Appends an adjustment that supersedes prior evidence without rewriting it. Idempotent on the
   * lineage key so a retried correction does not stack duplicates.
   */
  public Evidence appendAdjustmentEvidence(
      UUID learnerId, UUID skillId, UUID adjustsEvidenceId, String lineageKey,
      BigDecimal observedScore, BigDecimal normalizedScore, String interactionId) {
    // No coverage columns: an adjustment restates a score, it does not measure anything new.
    jdbcTemplate.update("""
        INSERT INTO ledger.evidence
          (id, learner_id, skill_id, evidence_type, source_type, adjusts_evidence_id, lineage_key,
           observed_score, normalized_score, interaction_id)
        VALUES (?, ?, ?, 'ADJUSTMENT', 'ADJUSTMENT', ?, ?, ?, ?, ?)
        ON CONFLICT (lineage_key) DO NOTHING
        """, UuidV7.generate(), learnerId, skillId, adjustsEvidenceId, lineageKey,
        observedScore, normalizedScore, interactionId);
    return requireByLineage(lineageKey);
  }

  public Optional<Evidence> findByLineageKey(String lineageKey) {
    return jdbcTemplate.query(EVIDENCE_SELECT + " WHERE lineage_key = ?", EVIDENCE_MAPPER, lineageKey)
        .stream().findFirst();
  }

  public Optional<Evidence> findById(UUID id) {
    return jdbcTemplate.query(EVIDENCE_SELECT + " WHERE id = ?", EVIDENCE_MAPPER, id)
        .stream().findFirst();
  }

  public List<Evidence> findByLearnerAndSkill(UUID learnerId, UUID skillId) {
    return jdbcTemplate.query(
        EVIDENCE_SELECT + " WHERE learner_id = ? AND skill_id = ? ORDER BY occurred_at, id",
        EVIDENCE_MAPPER, learnerId, skillId);
  }

  private Evidence requireByLineage(String lineageKey) {
    return findByLineageKey(lineageKey).orElseThrow(
        () -> new IllegalStateException("Evidence append did not persist a row."));
  }

  private static final String EVIDENCE_SELECT = """
      SELECT id, learner_id, skill_id, evidence_type, source_type, source_attempt_id,
             source_assessment_version_id, scoring_version, adjusts_evidence_id, lineage_key,
             observed_score, normalized_score, items_answered, items_correct,
             covered_objective_ids, covered_difficulty_bands, interaction_id,
             occurred_at, recorded_at
      FROM ledger.evidence
      """;

  private static final RowMapper<Evidence> EVIDENCE_MAPPER = (result, row) -> new Evidence(
      result.getObject("id", UUID.class),
      result.getObject("learner_id", UUID.class),
      result.getObject("skill_id", UUID.class),
      result.getString("evidence_type"),
      result.getString("source_type"),
      result.getObject("source_attempt_id", UUID.class),
      result.getObject("source_assessment_version_id", UUID.class),
      result.getString("scoring_version"),
      result.getObject("adjusts_evidence_id", UUID.class),
      result.getString("lineage_key"),
      result.getBigDecimal("observed_score"),
      result.getBigDecimal("normalized_score"),
      result.getInt("items_answered"),
      result.getInt("items_correct"),
      coverage(result),
      result.getString("interaction_id"),
      instant(result, "occurred_at"),
      instant(result, "recorded_at"));

  /**
   * Reads an evidence row's coverage. A NULL array is coverage nobody recorded -- every row written
   * before V046 -- and becomes {@link EvidenceCoverage#none()} rather than anything that would
   * credit the learner for breadth the ledger cannot show.
   */
  private static EvidenceCoverage coverage(ResultSet result) throws SQLException {
    List<UUID> objectives = new ArrayList<>();
    for (Object element : elements(result.getArray("covered_objective_ids"))) {
      objectives.add(element instanceof UUID id ? id : UUID.fromString(String.valueOf(element)));
    }
    Set<MasteryDifficultyBand> bands = new LinkedHashSet<>();
    for (Object element : elements(result.getArray("covered_difficulty_bands"))) {
      // Fail-closed: a band the vocabulary does not know is a defect, not a value to skip.
      bands.add(MasteryDifficultyBand.of(String.valueOf(element)));
    }
    return new EvidenceCoverage(objectives, bands);
  }

  private static Object[] elements(Array array) throws SQLException {
    if (array == null) {
      return new Object[0];
    }
    Object raw = array.getArray();
    return raw == null ? new Object[0] : (Object[]) raw;
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
