package io.ramals.learningplatform.content;

import io.ramals.learningplatform.admin.AdminActivityRepository;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** M1-T12 durable, retry-safe approval state machine. */
@Service
public class ApprovalRequestService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApprovalRequestService.class);
  private static final String POLICY_VERSION = "m1-t12-policy-v1";
  private static final String ENGINE_VERSION = "spring-content-promotion-v1";
  private static final Duration APPROVAL_TTL = Duration.ofHours(24);

  private final ApprovalRequestRepository approvals;
  private final AssessmentCandidateRevisionRepository candidates;
  private final AdminActivityRepository audit;
  private final Clock clock;

  @Autowired
  public ApprovalRequestService(ApprovalRequestRepository approvals,
      AssessmentCandidateRevisionRepository candidates, AdminActivityRepository audit) {
    this(approvals, candidates, audit, Clock.systemUTC());
  }

  ApprovalRequestService(ApprovalRequestRepository approvals,
      AssessmentCandidateRevisionRepository candidates, AdminActivityRepository audit, Clock clock) {
    this.approvals = approvals;
    this.candidates = candidates;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  public ApprovalRequest create(UUID candidateId, int revision, String actor, String key) {
    requireKey(key);
    String fingerprint = fingerprint("CREATE", candidateId + ":" + revision);
    var previous = approvals.findByCreateCommand(actor, key);
    if (previous.isPresent()) {
      assertFingerprint(actor, "CREATE", previous.get().id(), key, fingerprint);
      return previous.get();
    }
    AssessmentCandidateRevision candidate = candidates.findByIdForUpdate(candidateId, revision)
        .orElseThrow(() -> error("CANDIDATE_NOT_FOUND", "candidate revision does not exist"));
    if (!"UNVERIFIED".equals(candidate.trustState())) {
      throw error("CANDIDATE_NOT_ELIGIBLE", "candidate is not awaiting approval");
    }
    ApprovalRequestRepository.InsertResult insertion = approvals.insertRequest(candidate, POLICY_VERSION, ENGINE_VERSION, actor,
        Instant.now(clock).plus(APPROVAL_TTL));
    if (!insertion.created()) {
      var concurrent = approvals.findByCreateCommand(actor, key);
      if (concurrent.isPresent()) {
        assertFingerprint(actor, "CREATE", concurrent.get().id(), key, fingerprint);
        return concurrent.get();
      }
      throw error("APPROVAL_ALREADY_EXISTS", "candidate already has an approval request");
    }
    ApprovalRequest request = insertion.request();
    approvals.insertCommand(actor, "CREATE", request.id(), key, fingerprint, request.state(), null);
    audit.appendWithinTransaction(actor, "CREATE_APPROVAL_REQUEST", "ASSESSMENT_CANDIDATE",
        request.id(), "SUCCESS", "candidate=" + candidateId + "; revision=" + revision,
        CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
    event("content.approval.requested", "Approval request created", request.id(),
        null, ApprovalState.APPROVAL_REQUIRED, "SUCCESS", null);
    return request;
  }

  @PreAuthorize("hasAnyRole('CONTENT_AUTHOR', 'ADMIN')")
  @Transactional
  public ApprovalRequest get(UUID id) {
    ApprovalRequest request = approvals.findForUpdate(id)
        .orElseThrow(() -> error("APPROVAL_NOT_FOUND", "approval request does not exist"));
    if (request.state() == ApprovalState.APPROVAL_REQUIRED
        && !Instant.now(clock).isBefore(request.expiresAt())) {
      approvals.transition(id, ApprovalState.APPROVAL_REQUIRED, ApprovalState.EXPIRED, "system",
          "approval request expired", null);
      auditWithin("system", id, "EXPIRED", "approval request expired");
      event("content.approval.expired", "Approval request expired", id,
          ApprovalState.APPROVAL_REQUIRED, ApprovalState.EXPIRED, "REJECTED", "APPROVAL_EXPIRED");
      return approvals.find(id).orElseThrow();
    }
    return request;
  }

  @Transactional
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  public ApprovalRequest approve(UUID id, String actor, String key) {
    requireKey(key);
    return review(id, actor, key, "APPROVE", null);
  }

  @Transactional
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  public ApprovalRequest reject(UUID id, String actor, String key, String reason) {
    requireKey(key);
    if (reason == null || reason.isBlank() || reason.length() > 1024) {
      throw error("REVIEW_REASON_REQUIRED", "rejection requires a non-blank reason of at most 1024 characters");
    }
    return review(id, actor, key, "REJECT", reason.trim());
  }

  @Transactional
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  public ApprovalRequest cancel(UUID id, String actor, String key) {
    requireKey(key);
    return review(id, actor, key, "CANCEL", "cancelled by owner");
  }

  private ApprovalRequest review(UUID id, String actor, String key, String operation, String reason) {
    String fingerprint = fingerprint(operation, id + ":" + (reason == null ? "" : reason));
    var request = approvals.findForUpdate(id)
        .orElseThrow(() -> error("APPROVAL_NOT_FOUND", "approval request does not exist"));
    var prior = approvals.findCommand(actor, operation, id, key);
    if (prior.isPresent()) {
      if (!prior.get().fingerprint().equals(fingerprint)) {
        throw error("IDEMPOTENCY_CONFLICT", "Idempotency-Key was reused for a different command");
      }
      return approvals.find(id).orElseThrow();
    }
    Instant now = Instant.now(clock);
    if (request.state() != ApprovalState.APPROVAL_REQUIRED) {
      throw error("APPROVAL_STATE_CONFLICT", "approval request is already terminal");
    }
    if (!now.isBefore(request.expiresAt())) {
      approvals.transition(id, ApprovalState.APPROVAL_REQUIRED, ApprovalState.EXPIRED, actor,
          "approval request expired", null);
      approvals.insertCommand(actor, operation, id, key, fingerprint, ApprovalState.EXPIRED, null);
      auditWithin(actor, id, "EXPIRED", "approval request expired");
      event("content.approval.expired", "Approval request expired", id,
          ApprovalState.APPROVAL_REQUIRED, ApprovalState.EXPIRED, "REJECTED", "APPROVAL_EXPIRED");
      return approvals.find(id).orElseThrow();
    }
    if ("REJECT".equals(operation)) {
      approvals.transition(id, ApprovalState.APPROVAL_REQUIRED, ApprovalState.REJECTED, actor, reason, null);
      approvals.insertCommand(actor, operation, id, key, fingerprint, ApprovalState.REJECTED, null);
      auditWithin(actor, id, "REJECTED", reason);
      event("content.rejected", "Approval request rejected", id,
          ApprovalState.APPROVAL_REQUIRED, ApprovalState.REJECTED, "REJECTED", "CONTENT_REJECTED");
      return approvals.find(id).orElseThrow();
    }
    if ("CANCEL".equals(operation)) {
      if (!actor.equals(request.createdBy())) {
        throw error("CANCEL_NOT_AUTHORIZED", "only the approval request owner may cancel it");
      }
      approvals.transition(id, ApprovalState.APPROVAL_REQUIRED, ApprovalState.CANCELLED, actor, reason, null);
      approvals.insertCommand(actor, operation, id, key, fingerprint, ApprovalState.CANCELLED, null);
      auditWithin(actor, id, "CANCELLED", reason);
      event("content.approval.cancelled", "Approval request cancelled", id,
          ApprovalState.APPROVAL_REQUIRED, ApprovalState.CANCELLED, "SUCCESS", null);
      return approvals.find(id).orElseThrow();
    }

    AssessmentCandidateRevision candidate = approvals.candidateForUpdate(request.candidateId(), request.candidateRevision())
        .orElseThrow(() -> error("CANDIDATE_NOT_FOUND", "candidate revision does not exist"));
    if (!candidate.proposalDigest().equals(request.proposalDigest()) || !approvals.candidateStillEligible(candidate)) {
      approvals.transition(id, ApprovalState.APPROVAL_REQUIRED, ApprovalState.SUPERSEDED, actor,
          "final deterministic revalidation failed", null);
      approvals.insertCommand(actor, operation, id, key, fingerprint, ApprovalState.SUPERSEDED, null);
      auditWithin(actor, id, "SUPERSEDED", "final deterministic revalidation failed");
      event("content.superseded", "Approval request superseded after revalidation", id,
          ApprovalState.APPROVAL_REQUIRED, ApprovalState.SUPERSEDED, "REJECTED", "DETERMINISTIC_REVALIDATION_FAILED");
      return approvals.find(id).orElseThrow();
    }
    UUID itemId = approvals.promoteCandidate(candidate, actor);
    approvals.transition(id, ApprovalState.APPROVAL_REQUIRED, ApprovalState.APPROVED, actor,
        "human approval and final deterministic revalidation", itemId);
    approvals.insertCommand(actor, operation, id, key, fingerprint, ApprovalState.APPROVED, itemId);
    auditWithin(actor, id, "APPROVED", "authoritative item=" + itemId);
    event("content.approved", "Content approval completed", id,
        ApprovalState.APPROVAL_REQUIRED, ApprovalState.APPROVED, "SUCCESS", null);
    return approvals.find(id).orElseThrow();
  }

  private void event(String operation, String message, UUID requestId,
      ApprovalState stateFrom, ApprovalState stateTo, String outcome, String errorCode) {
    Map<String, Object> fields = new java.util.HashMap<>();
    fields.put("entityType", "ASSESSMENT_APPROVAL_REQUEST");
    fields.put("entityId", requestId);
    if (stateFrom != null) fields.put("stateFrom", stateFrom);
    if (stateTo != null) fields.put("stateTo", stateTo);
    fields.put("policyVersion", POLICY_VERSION);
    fields.put("outcome", outcome);
    if (errorCode != null) fields.put("errorCode", errorCode);
    if ("SUCCESS".equals(outcome)) {
      BusinessEventLogger.info(LOGGER, operation, message, fields);
    } else {
      BusinessEventLogger.warn(LOGGER, operation, message, fields);
    }
  }

  private void auditWithin(String actor, UUID id, String outcome, String detail) {
    audit.appendWithinTransaction(actor, "REVIEW_APPROVAL_REQUEST", "ASSESSMENT_APPROVAL_REQUEST", id,
        outcome, detail, CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
  }

  private void assertFingerprint(String actor, String operation, UUID requestId, String key, String expected) {
    var command = approvals.findCommand(actor, operation, requestId, key)
        .orElseThrow(() -> error("IDEMPOTENCY_CONFLICT", "idempotency command record is missing"));
    if (!command.fingerprint().equals(expected)) {
      throw error("IDEMPOTENCY_CONFLICT", "Idempotency-Key was reused for a different command");
    }
  }

  private static String fingerprint(String operation, String value) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest((operation + "\n" + value)
          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 is required by the JDK", ex);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) value.append(String.format("%02x", b));
    return value.toString();
  }

  private static void requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 255) {
      throw error("IDEMPOTENCY_KEY_REQUIRED", "a valid Idempotency-Key header is required");
    }
  }

  private static ApprovalRequestException error(String code, String message) {
    return new ApprovalRequestException(code, message);
  }
}
