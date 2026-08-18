package io.ramals.learningplatform.content;

import io.ramals.learningplatform.admin.AdminActivityRepository;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the short, Spring-proxied transaction that persists an AI candidate and its intake audit. */
@Service
public class AssessmentCandidatePersistenceService {

  private static final String MODEL_ID_UNAVAILABLE =
      "current AI contract exposes modelRoute but not provider/model identity";

  private final AssessmentCandidateRevisionRepository repository;
  private final AdminActivityRepository auditRepository;

  public AssessmentCandidatePersistenceService(
      AssessmentCandidateRevisionRepository repository, AdminActivityRepository auditRepository) {
    this.repository = repository;
    this.auditRepository = auditRepository;
  }

  @Transactional
  public AssessmentCandidateRevision persist(
      CandidateContent candidate,
      AiProposalEnvelope proposal,
      AiRequestEnvelope request,
      String idempotencyActor,
      String idempotencyKey,
      String createdBy) {
    AssessmentCandidateRevision existing = repository.findByIdempotency(idempotencyActor, idempotencyKey)
        .orElse(null);
    Map<String, Object> approvalPayload = approvalPayload(candidate, proposal.proposal());
    String digest = CandidateCanonicalizer.sha256(approvalPayload);
    if (existing != null) {
      if (!existing.idempotencyFingerprint().equals(digest)) {
        throw new AssessmentCandidateIntakeService.CandidateIntakeConflictException(
            "Idempotency-Key was reused for different candidate content.");
      }
      return existing;
    }
    try {
      String interactionId = CorrelationContext.currentInteractionId();
      if (interactionId.isBlank() && request != null && request.interactionId() != null) {
        interactionId = request.interactionId();
      }
      AssessmentCandidateRevision saved = repository.insert(
          candidate, proposal.proposalId(), proposal.contractVersion(), proposal.agentType().name(),
          proposal.agentVersion(), proposal.modelRoute(), null, MODEL_ID_UNAVAILABLE,
          proposal.promptVersion(), interactionId, createdBy, idempotencyActor, idempotencyKey,
          digest, digest, approvalPayload);
      auditRepository.appendWithinTransaction(
          createdBy, "AI_CANDIDATE_INTAKE", "ASSESSMENT_CANDIDATE", saved.candidateId(), "SUCCESS",
          "revision=" + saved.candidateRevision() + "; digest=" + saved.proposalDigest()
              + "; trustState=UNVERIFIED; sourceProposalId=" + saved.sourceProposalId(),
          interactionId, CorrelationContext.currentTraceId());
      return saved;
    } catch (DuplicateKeyException duplicate) {
      AssessmentCandidateRevision raced = repository.findByIdempotency(idempotencyActor, idempotencyKey)
          .orElseThrow(() -> duplicate);
      if (!raced.idempotencyFingerprint().equals(digest)) {
        throw new AssessmentCandidateIntakeService.CandidateIntakeConflictException(
            "Idempotency-Key was reused for different candidate content.");
      }
      return raced;
    }
  }

  private static Map<String, Object> approvalPayload(
      CandidateContent candidate, Map<String, Object> original) {
    Map<String, Object> payload = new java.util.TreeMap<>();
    payload.putAll(CandidateCanonicalizer.payload(candidate));
    Object value = original.get("rationale");
    String rationale = value instanceof String text ? text : null;
    if (rationale == null || rationale.isBlank()) {
      throw new AssessmentCandidateIntakeService.CandidateIntakeRejectedException(
          "candidate field is missing: rationale");
    }
    payload.put("rationale", rationale);
    return payload;
  }
}
