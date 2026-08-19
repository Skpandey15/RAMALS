package io.ramals.learningplatform.content;

import io.ramals.learningplatform.observability.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Persistence boundary for immutable S0-07 candidate revisions. */
@Repository
public class AssessmentCandidateRevisionRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AssessmentCandidateRevisionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public Optional<AssessmentCandidateRevision> findByIdempotency(
      String actor, String key) {
    return jdbcTemplate.query(
        SELECT + " WHERE idempotency_actor = ? AND idempotency_key = ?",
        MAPPER, actor, key).stream().findFirst();
  }

  public Optional<AssessmentCandidateRevision> findById(UUID candidateId, int revision) {
    return jdbcTemplate.query(
        SELECT + " WHERE candidate_id = ? AND candidate_revision = ?",
        MAPPER, candidateId, revision).stream().findFirst();
  }

  /**
   * Locks a candidate revision while an approval request is being created. This serializes
   * concurrent CREATE commands for the same immutable candidate before the request command is
   * recorded, so a losing transaction can only resolve after the winner has committed both rows.
   */
  public Optional<AssessmentCandidateRevision> findByIdForUpdate(UUID candidateId, int revision) {
    return jdbcTemplate.query(
        SELECT + " WHERE candidate_id = ? AND candidate_revision = ? FOR UPDATE",
        MAPPER, candidateId, revision).stream().findFirst();
  }

  public AssessmentCandidateRevision insert(
      CandidateContent candidate,
      String sourceProposalId,
      String contractVersion,
      String agentType,
      String agentVersion,
      String modelRoute,
      String modelId,
      String modelIdUnavailableReason,
      String promptVersion,
      String interactionId,
      String createdBy,
      String idempotencyActor,
      String idempotencyKey,
      String idempotencyFingerprint,
      String proposalDigest,
      Map<String, Object> payload) {
    UUID candidateId = UuidV7.generate();
    String payloadJson = objectMapper.writeValueAsString(payload);
    jdbcTemplate.update("""
        INSERT INTO core.assessment_candidate_revision
          (candidate_id, candidate_revision, source_proposal_id, assessment_version_id,
           item_code, skill_code, objective_code, item_type, difficulty,
           candidate_payload_jsonb, proposal_digest, trust_state, contract_version,
           agent_type, agent_version, model_route, model_id, model_id_unavailable_reason,
           prompt_version, interaction_id, created_by, idempotency_actor, idempotency_key,
           idempotency_fingerprint)
        VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'UNVERIFIED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, candidateId, sourceProposalId, candidate.assessmentVersionId(), candidate.itemCode(),
        candidate.skillCode(), candidate.objectiveCode(), candidate.itemType(), candidate.difficulty(),
        payloadJson, proposalDigest, contractVersion, agentType, agentVersion, modelRoute, modelId,
        modelIdUnavailableReason, promptVersion, interactionId, createdBy, idempotencyActor,
        idempotencyKey, idempotencyFingerprint);
    return findById(candidateId, 1)
        .orElseThrow(() -> new IllegalStateException("Candidate revision insert did not persist."));
  }

  private static final String SELECT = """
      SELECT candidate_id, candidate_revision, source_proposal_id, assessment_version_id,
             item_code, skill_code, objective_code, item_type, difficulty,
             candidate_payload_jsonb::text AS candidate_payload_json,
             proposal_digest, trust_state, contract_version, agent_type, agent_version,
             model_route, model_id, model_id_unavailable_reason, prompt_version,
             interaction_id, created_by, created_at, idempotency_actor, idempotency_key,
             idempotency_fingerprint
        FROM core.assessment_candidate_revision
      """;

  private static final RowMapper<AssessmentCandidateRevision> MAPPER =
      (result, row) -> new AssessmentCandidateRevision(
          result.getObject("candidate_id", UUID.class),
          result.getInt("candidate_revision"),
          result.getString("source_proposal_id"),
          result.getObject("assessment_version_id", UUID.class),
          result.getString("item_code"),
          result.getString("skill_code"),
          result.getString("objective_code"),
          result.getString("item_type"),
          result.getString("difficulty"),
          result.getString("candidate_payload_json"),
          result.getString("proposal_digest"),
          result.getString("trust_state"),
          result.getString("contract_version"),
          result.getString("agent_type"),
          result.getString("agent_version"),
          result.getString("model_route"),
          result.getString("model_id"),
          result.getString("model_id_unavailable_reason"),
          result.getString("prompt_version"),
          result.getString("interaction_id"),
          result.getString("created_by"),
          instant(result, "created_at"),
          result.getString("idempotency_actor"),
          result.getString("idempotency_key"),
          result.getString("idempotency_fingerprint"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
