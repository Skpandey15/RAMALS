package io.ramals.learningplatform.content;

import io.ramals.learningplatform.observability.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ApprovalRequestRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public ApprovalRequestRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<ApprovalRequest> find(UUID id) {
    return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
  }

  public Optional<ApprovalRequest> findForUpdate(UUID id) {
    return jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
  }

  public Optional<ApprovalRequest> findByCandidate(UUID candidateId, int revision) {
    return jdbc.query(SELECT + " WHERE candidate_id = ? AND candidate_revision = ?", MAPPER,
        candidateId, revision).stream().findFirst();
  }

  public Optional<ApprovalRequest> findByCreateCommand(String actor, String key) {
    return jdbc.query(SELECT + " WHERE id = (SELECT request_id FROM core.assessment_approval_command "
        + "WHERE actor_subject = ? AND operation = 'CREATE' AND idempotency_key = ?)", MAPPER,
        actor, key).stream().findFirst();
  }

  public Optional<CommandResult> findCommand(String actor, String operation, UUID requestId, String key) {
    return jdbc.query("""
        SELECT result_state, authoritative_item_version_id, request_fingerprint
          FROM core.assessment_approval_command
         WHERE actor_subject = ? AND operation = ? AND request_id = ? AND idempotency_key = ?
        """, (r, n) -> new CommandResult(ApprovalState.valueOf(r.getString("result_state")),
        r.getObject("authoritative_item_version_id", UUID.class), r.getString("request_fingerprint")),
        actor, operation, requestId, key).stream().findFirst();
  }

  public InsertResult insertRequest(AssessmentCandidateRevision candidate, String policyVersion,
      String engineVersion, String actor, Instant expiresAt) {
    UUID id = UuidV7.generate();
    jdbc.update("""
        INSERT INTO core.assessment_approval_request
          (id, candidate_id, candidate_revision, target_type, candidate_payload_jsonb,
           proposal_digest, source_proposal_id, contract_version, agent_type, agent_version,
           model_route, model_id, prompt_version, policy_version, engine_version, interaction_id,
           created_by, expires_at)
        VALUES (?, ?, ?, 'ASSESSMENT_CANDIDATE', ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (candidate_id, candidate_revision) DO NOTHING
        """, id, candidate.candidateId(), candidate.candidateRevision(), candidate.candidatePayloadJson(),
        candidate.proposalDigest(), candidate.sourceProposalId(), candidate.contractVersion(),
        candidate.agentType(), candidate.agentVersion(), candidate.modelRoute(), candidate.modelId(),
        candidate.promptVersion(), policyVersion, engineVersion, candidate.interactionId(), actor, expiresAt);
    ApprovalRequest request = findByCandidate(candidate.candidateId(), candidate.candidateRevision())
        .orElseThrow(() -> new IllegalStateException("Approval request insert did not persist."));
    return new InsertResult(request, request.id().equals(id));
  }

  public void insertCommand(String actor, String operation, UUID requestId, String key,
      String fingerprint, ApprovalState state, UUID itemId) {
    jdbc.update("""
        INSERT INTO core.assessment_approval_command
          (actor_subject, operation, request_id, idempotency_key, request_fingerprint,
           result_state, authoritative_item_version_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, actor, operation, requestId, key, fingerprint, state.name(), itemId);
  }

  public void transition(UUID id, ApprovalState from, ApprovalState to, String reviewer,
      String reason, UUID itemId) {
    int changed = jdbc.update("""
        UPDATE core.assessment_approval_request
           SET state = ?, reviewer_subject = ?, reviewed_at = CURRENT_TIMESTAMP,
               review_reason = ?, authoritative_item_version_id = ?
         WHERE id = ? AND state = ?
        """, to.name(), reviewer, reason, itemId, id, from.name());
    if (changed != 1) {
      throw new ApprovalRequestException("APPROVAL_STATE_CONFLICT", "approval request changed concurrently");
    }
  }

  public int expireDue(Instant now) {
    return jdbc.update("""
        UPDATE core.assessment_approval_request
           SET state = 'EXPIRED', reviewer_subject = 'system', reviewed_at = CURRENT_TIMESTAMP,
               review_reason = 'approval request expired'
         WHERE state = 'APPROVAL_REQUIRED' AND expires_at <= ?
        """, now);
  }

  public Optional<AssessmentCandidateRevision> candidateForUpdate(UUID id, int revision) {
    return jdbc.query("""
        SELECT candidate_id, candidate_revision, source_proposal_id, assessment_version_id,
               item_code, skill_code, objective_code, item_type, difficulty,
               candidate_payload_jsonb::text AS candidate_payload_json, proposal_digest, trust_state,
               contract_version, agent_type, agent_version, model_route, model_id,
               model_id_unavailable_reason, prompt_version, interaction_id, created_by, created_at,
               idempotency_actor, idempotency_key, idempotency_fingerprint
          FROM core.assessment_candidate_revision
         WHERE candidate_id = ? AND candidate_revision = ? FOR UPDATE
        """, CandidateCandidateMapper.MAPPER, id, revision).stream().findFirst();
  }

  /** Performs the final deterministic checks while the approval row is locked. */
  public boolean candidateStillEligible(AssessmentCandidateRevision c) {
    Integer count = jdbc.queryForObject("""
        SELECT count(*)
          FROM core.assessment_version av
          JOIN core.curriculum_version cv ON cv.id = av.curriculum_version_id
          JOIN core.skill_version sv ON sv.curriculum_version_id = cv.id
          JOIN core.skill s ON s.id = sv.skill_id
         WHERE av.id = ? AND av.status = 'DRAFT' AND cv.status IN ('PUBLISHED', 'RETIRED')
           AND s.stable_code = ?
           AND (? IS NULL OR EXISTS (
             SELECT 1 FROM core.learning_objective o
              WHERE o.skill_version_id = sv.id AND o.objective_code = ?))
           AND (sv.required_difficulty_bands IS NULL OR ? = ANY(sv.required_difficulty_bands))
        """, Integer.class, c.assessmentVersionId(), c.skillCode(), c.objectiveCode(),
        c.objectiveCode(), c.difficulty());
    return count != null && count > 0 && "UNVERIFIED".equals(c.trustState());
  }

  /** Creates the authoritative item while the caller's approval transaction is open. */
  public UUID promoteCandidate(AssessmentCandidateRevision c, String reviewer) {
    UUID itemId = UuidV7.generate();
    jdbc.queryForObject("SELECT id FROM core.assessment_version WHERE id = ? FOR UPDATE",
        UUID.class, c.assessmentVersionId());
    Integer nextOrder = jdbc.queryForObject("""
        SELECT COALESCE(MAX(display_order), 0) + 1 FROM core.assessment_item_version
         WHERE assessment_version_id = ?
        """, Integer.class, c.assessmentVersionId());
    try {
      int inserted = jdbc.update("""
          INSERT INTO core.assessment_item_version
            (id, assessment_version_id, skill_id, item_code, item_type, stem, options_jsonb,
             answer_key_jsonb, difficulty, display_order, trust_state, verified_by, verified_at)
          SELECT ?, ?, s.id, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'VERIFIED_CONTENT', ?, CURRENT_TIMESTAMP
            FROM core.skill s
           WHERE s.stable_code = ?
          """, itemId, c.assessmentVersionId(), c.itemCode(), c.itemType(),
          payloadOptions(c), payloadAnswerKey(c), c.difficulty(), nextOrder, reviewer, c.skillCode());
      if (inserted != 1) {
        throw new ApprovalRequestException("PROMOTION_CONFLICT",
            "authoritative promotion did not insert exactly one assessment item");
      }
    } catch (DuplicateKeyException duplicate) {
      throw new ApprovalRequestException("PROMOTION_CONFLICT", "candidate item code or order already exists");
    }
    return itemId;
  }

  private String payloadOptions(AssessmentCandidateRevision c) {
    try {
      var payload = mapper.readValue(c.candidatePayloadJson(), java.util.Map.class);
      var ids = (java.util.List<?>) payload.get("options");
      var options = ids.stream().map(id -> java.util.Map.of("id", String.valueOf(id), "text", String.valueOf(id))).toList();
      return mapper.writeValueAsString(options);
    } catch (RuntimeException ex) {
      throw new ApprovalRequestException("CANDIDATE_PAYLOAD_INVALID", "candidate options cannot be reconstructed");
    }
  }

  private String payloadAnswerKey(AssessmentCandidateRevision c) {
    try {
      var payload = mapper.readValue(c.candidatePayloadJson(), java.util.Map.class);
      return mapper.writeValueAsString(java.util.Map.of("correct", payload.get("answerKey")));
    } catch (RuntimeException ex) {
      throw new ApprovalRequestException("CANDIDATE_PAYLOAD_INVALID", "candidate answer key cannot be reconstructed");
    }
  }

  public record CommandResult(ApprovalState state, UUID itemId, String fingerprint) {}

  public record InsertResult(ApprovalRequest request, boolean created) {}

  private static final String SELECT = """
      SELECT id, candidate_id, candidate_revision, state, candidate_payload_jsonb::text AS candidate_payload_json,
             proposal_digest, source_proposal_id, contract_version, agent_type, agent_version,
             model_route, model_id, prompt_version, policy_version, engine_version, interaction_id,
             created_by, created_at, updated_at, expires_at, reviewer_subject, reviewed_at,
             review_reason, authoritative_item_version_id
        FROM core.assessment_approval_request
      """;

  private static final RowMapper<ApprovalRequest> MAPPER = (r, n) -> new ApprovalRequest(
      r.getObject("id", UUID.class), r.getObject("candidate_id", UUID.class), r.getInt("candidate_revision"),
      ApprovalState.valueOf(r.getString("state")), r.getString("candidate_payload_json"), r.getString("proposal_digest"),
      r.getString("source_proposal_id"), r.getString("contract_version"), r.getString("agent_type"),
      r.getString("agent_version"), r.getString("model_route"), r.getString("model_id"), r.getString("prompt_version"),
      r.getString("policy_version"), r.getString("engine_version"), r.getString("interaction_id"),
      r.getString("created_by"), instant(r, "created_at"), instant(r, "updated_at"), instant(r, "expires_at"),
      r.getString("reviewer_subject"), instant(r, "reviewed_at"), r.getString("review_reason"),
      r.getObject("authoritative_item_version_id", UUID.class));

  private static Instant instant(ResultSet r, String c) throws SQLException {
    OffsetDateTime value = r.getObject(c, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static final class CandidateCandidateMapper {
    private static final RowMapper<AssessmentCandidateRevision> MAPPER = (r, n) -> new AssessmentCandidateRevision(
        r.getObject("candidate_id", UUID.class), r.getInt("candidate_revision"), r.getString("source_proposal_id"),
        r.getObject("assessment_version_id", UUID.class), r.getString("item_code"), r.getString("skill_code"),
        r.getString("objective_code"), r.getString("item_type"), r.getString("difficulty"),
        r.getString("candidate_payload_json"), r.getString("proposal_digest"), r.getString("trust_state"),
        r.getString("contract_version"), r.getString("agent_type"), r.getString("agent_version"),
        r.getString("model_route"), r.getString("model_id"), r.getString("model_id_unavailable_reason"),
        r.getString("prompt_version"), r.getString("interaction_id"), r.getString("created_by"),
        instant(r, "created_at"), r.getString("idempotency_actor"), r.getString("idempotency_key"),
        r.getString("idempotency_fingerprint"));
  }
}
