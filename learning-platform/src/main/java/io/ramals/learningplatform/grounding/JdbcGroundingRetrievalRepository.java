package io.ramals.learningplatform.grounding;

import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL retrieval that applies ownership, publication, trust, time, ordering, and limits. */
@Repository
public class JdbcGroundingRetrievalRepository implements GroundingRetrievalPort {
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private final JdbcTemplate jdbcTemplate;

  public JdbcGroundingRetrievalRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<AuthorizedGroundingFacts> retrieve(
      String authenticatedSubject,
      UUID curriculumVersionId,
      Instant asOf,
      GroundingRetrievalPolicy policy) {
    try {
      return jdbcTemplate.execute((ConnectionCallback<Optional<AuthorizedGroundingFacts>>) connection -> {
        try (PreparedStatement statement = connection.prepareStatement(RETRIEVAL_SQL)) {
          statement.setQueryTimeout(Math.max(1, (int) Math.ceil(policy.timeout().toMillis() / 1000.0)));
          statement.setString(1, authenticatedSubject);
          statement.setObject(2, curriculumVersionId);
          statement.setTimestamp(3, Timestamp.from(asOf));
          statement.setInt(4, policy.evidenceLimit());
          statement.setObject(5, curriculumVersionId);
          statement.setTimestamp(6, Timestamp.from(asOf));
          statement.setInt(7, policy.masteryLimit());
          statement.setObject(8, curriculumVersionId);
          statement.setInt(9, policy.skillGraphLimit());
          statement.setObject(10, curriculumVersionId);
          statement.setInt(11, policy.curriculumPolicyLimit());
          statement.setObject(12, curriculumVersionId);
          statement.setInt(13, policy.approvedContentLimit());
          try (ResultSet result = statement.executeQuery()) {
            UUID learnerId = null;
            List<GroundedContextItem> items = new ArrayList<>();
            while (result.next()) {
              learnerId = result.getObject("learner_id", UUID.class);
              if (result.getString("evidence_id") != null) {
                items.add(mapItem(result));
              }
            }
            return learnerId == null
                ? Optional.empty()
                : Optional.of(new AuthorizedGroundingFacts(learnerId, items));
          }
        }
      });
    } catch (DataAccessException failure) {
      throw new GroundingRetrievalException("GROUNDING_RETRIEVAL_FAILED");
    }
  }

  @Override
  public void appendRetrievalRecord(GroundedContext context, UUID learnerId) {
    List<String> sourceRefs = context.items().stream()
        .map(item -> item.sourceType().name() + ":" + item.evidenceId() + ":"
            + item.sourceVersion())
        .toList();
    jdbcTemplate.update("""
        INSERT INTO ledger.grounding_retrieval_record
          (context_id, learner_id, retrieval_policy_version, as_of, expires_at,
           source_refs, source_count)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
        ON CONFLICT (context_id) DO NOTHING
        """, context.contextId(), learnerId, context.retrievalPolicyVersion(),
        Timestamp.from(context.asOf()), Timestamp.from(context.expiresAt()),
        JSON.writeValueAsString(sourceRefs), sourceRefs.size());
  }

  private static GroundedContextItem mapItem(ResultSet result) throws SQLException {
    return new GroundedContextItem(
        result.getString("evidence_id"),
        SourceType.valueOf(result.getString("source_type")),
        result.getString("source_version"),
        ContextAuthority.AUTHORITATIVE_FACT,
        result.getString("fact_type"),
        result.getString("fact_value"),
        instant(result, "observed_at"),
        null);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value.toInstant();
  }

  /*
   * The authorized learner CTE is deliberately the root of every branch. There is no learner-id
   * argument a caller can swap. UNION ALL branches are individually capped before the final stable
   * sort, and content must be both in a published curriculum and VERIFIED_CONTENT.
   */
  private static final String RETRIEVAL_SQL = """
      WITH authorized_learner AS (
        SELECT id FROM core.learner WHERE subject = ? AND status = 'ACTIVE'
      ), evidence_facts AS (
        SELECT learner.id AS learner_id, evidence.id::text AS evidence_id,
               'LEARNER_EVIDENCE' AS source_type,
               COALESCE(evidence.scoring_version, 'ledger-v1') AS source_version,
               'NORMALIZED_SCORE' AS fact_type, evidence.normalized_score::text AS fact_value,
               evidence.occurred_at AS observed_at
        FROM authorized_learner learner
        JOIN ledger.evidence evidence ON evidence.learner_id = learner.id
        JOIN core.skill_version skill_version ON skill_version.skill_id = evidence.skill_id
        WHERE skill_version.curriculum_version_id = ? AND evidence.occurred_at <= ?
        ORDER BY evidence.occurred_at DESC, evidence.id
        LIMIT ?
      ), mastery_facts AS (
        SELECT learner_id, evidence_id, source_type, source_version, fact_type, fact_value, observed_at
        FROM (
          SELECT learner.id AS learner_id, snapshot.id::text AS evidence_id,
                 'MASTERY' AS source_type,
                 snapshot.algorithm_version || ':' || snapshot.aggregate_version AS source_version,
                 'MASTERY_SCORE' AS fact_type, snapshot.mastery_score::text AS fact_value,
                 snapshot.calculated_at AS observed_at,
                 row_number() OVER (
                   PARTITION BY snapshot.skill_id ORDER BY snapshot.aggregate_version DESC) AS rank
          FROM authorized_learner learner
          JOIN ledger.mastery_snapshot snapshot ON snapshot.learner_id = learner.id
          WHERE snapshot.curriculum_version_id = ? AND snapshot.calculated_at <= ?
        ) latest WHERE rank = 1
        ORDER BY evidence_id LIMIT ?
      ), skill_facts AS (
        SELECT learner.id AS learner_id, skill_version.id::text AS evidence_id,
               'SKILL_GRAPH' AS source_type, curriculum.version_code AS source_version,
               'SKILL_CODE' AS fact_type, skill.stable_code AS fact_value,
               curriculum.published_at AS observed_at
        FROM authorized_learner learner
        JOIN core.curriculum_version curriculum ON curriculum.id = ?
          AND curriculum.status IN ('PUBLISHED', 'RETIRED')
        JOIN core.skill_version skill_version ON skill_version.curriculum_version_id = curriculum.id
        JOIN core.skill skill ON skill.id = skill_version.skill_id
        ORDER BY skill_version.display_order, skill.stable_code LIMIT ?
      ), curriculum_policy_facts AS (
        SELECT learner.id AS learner_id, skill_version.id::text AS evidence_id,
               'CURRICULUM_POLICY' AS source_type, curriculum.version_code AS source_version,
               'MASTERY_THRESHOLD' AS fact_type,
               skill_version.mastery_threshold::text AS fact_value,
               curriculum.published_at AS observed_at
        FROM authorized_learner learner
        JOIN core.curriculum_version curriculum ON curriculum.id = ?
          AND curriculum.status IN ('PUBLISHED', 'RETIRED')
        JOIN core.skill_version skill_version ON skill_version.curriculum_version_id = curriculum.id
        ORDER BY skill_version.display_order, skill_version.id LIMIT ?
      ), content_facts AS (
        SELECT learner.id AS learner_id, item.id::text AS evidence_id,
               'APPROVED_CONTENT' AS source_type, assessment.version_code AS source_version,
               'APPROVED_ITEM_REF' AS fact_type, item.id::text AS fact_value,
               COALESCE(item.verified_at, assessment.published_at) AS observed_at
        FROM authorized_learner learner
        JOIN core.assessment_version assessment ON assessment.curriculum_version_id = ?
          AND assessment.status IN ('PUBLISHED', 'RETIRED')
        JOIN core.assessment_item_version item ON item.assessment_version_id = assessment.id
          AND item.trust_state = 'VERIFIED_CONTENT'
        ORDER BY item.display_order, item.id LIMIT ?
      ), selected AS (
        SELECT * FROM evidence_facts UNION ALL SELECT * FROM mastery_facts
        UNION ALL SELECT * FROM skill_facts UNION ALL SELECT * FROM curriculum_policy_facts
        UNION ALL SELECT * FROM content_facts
      )
      SELECT learner.id AS learner_id, selected.evidence_id, selected.source_type,
             selected.source_version, selected.fact_type, selected.fact_value, selected.observed_at
      FROM authorized_learner learner
      LEFT JOIN selected ON selected.learner_id = learner.id
      ORDER BY selected.source_type, selected.evidence_id, selected.fact_type
      """;
}
