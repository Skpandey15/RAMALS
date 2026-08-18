package io.ramals.learningplatform.content;

import io.ramals.learningplatform.admin.AdminActivityRepository;
import io.ramals.learningplatform.ai.AssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receives, validates, and durably records one AI assessment candidate. */
@Service
public class AssessmentCandidateIntakeService {

  private static final String MODEL_ID_UNAVAILABLE =
      "current AI contract exposes modelRoute but not provider/model identity";

  private final AssessmentPort assessmentPort;
  private final ContentValidationPipeline validationPipeline;
  private final AssessmentCandidateRevisionRepository repository;
  private final AdminActivityRepository auditRepository;

  public AssessmentCandidateIntakeService(
      AssessmentPort assessmentPort,
      ContentValidationPipeline validationPipeline,
      AssessmentCandidateRevisionRepository repository,
      AdminActivityRepository auditRepository) {
    this.assessmentPort = assessmentPort;
    this.validationPipeline = validationPipeline;
    this.repository = repository;
    this.auditRepository = auditRepository;
  }

  /** AI call and persistence are intentionally separate transaction boundaries. */
  public AssessmentCandidateRevision intake(
      UUID assessmentVersionId,
      AiRequestEnvelope request,
      String requestedDifficulty,
      ValidationContext validationContext,
      String idempotencyActor,
      String idempotencyKey,
      String createdBy,
      long deadlineMillis) {
    AiProposalEnvelope proposal = assessmentPort.requestAssessmentProposal(
        request, deadlineMillis, requestedDifficulty);
    CandidateContent candidate = candidateFrom(proposal, assessmentVersionId);
    ContentValidationPipeline.Outcome outcome = validationPipeline.validate(candidate, validationContext);
    if (outcome.rejected()) {
      throw new CandidateIntakeRejectedException(((ContentValidationPipeline.Outcome.Rejected) outcome).reason());
    }
    if (proposal.agentType() != AgentType.ASSESSMENT || proposal.trustLevel() != TrustLevel.UNVERIFIED) {
      throw new CandidateIntakeRejectedException("proposal is not an UNVERIFIED assessment candidate");
    }
    return persist(
        candidate, proposal, request, idempotencyActor, idempotencyKey, createdBy);
  }

  @Transactional
  AssessmentCandidateRevision persist(
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
        throw new CandidateIntakeConflictException("Idempotency-Key was reused for different candidate content.");
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
        throw new CandidateIntakeConflictException(
            "Idempotency-Key was reused for different candidate content.");
      }
      return raced;
    }
  }

  private CandidateContent candidateFrom(AiProposalEnvelope proposal, UUID assessmentVersionId) {
    Map<String, Object> payload = proposal.proposal();
    if (payload == null) {
      throw new CandidateIntakeRejectedException("candidate proposal payload is missing");
    }
    String sourceProposalId = string(proposal.proposalId(), "proposalId");
    return new CandidateContent(
        assessmentVersionId,
        itemCode(sourceProposalId, payload),
        string(payload, "skillCode"),
        nullableString(payload, "objectiveCode"),
        "SINGLE_CHOICE",
        string(payload, "stem"),
        optionIds(payload.get("options")),
        correctIds(payload.get("answerKey")),
        string(payload, "difficulty"));
  }

  private static String itemCode(String sourceProposalId, Map<String, Object> payload) {
    String supplied = nullableString(payload, "itemCode");
    if (supplied != null && !supplied.isBlank()) {
      return supplied;
    }
    String suffix = sourceProposalId.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    if (suffix.length() > 88) {
      suffix = suffix.substring(0, 88);
    }
    return "AI_CANDIDATE_" + suffix;
  }

  private static String string(Map<String, Object> payload, String key) {
    String value = nullableString(payload, key);
    if (value == null || value.isBlank()) {
      throw new CandidateIntakeRejectedException("candidate field is missing: " + key);
    }
    return value;
  }

  private static String string(String value, String key) {
    if (value == null || value.isBlank()) {
      throw new CandidateIntakeRejectedException("candidate field is missing: " + key);
    }
    return value;
  }

  private static String nullableString(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    return value instanceof String text ? text : null;
  }

  private static List<String> optionIds(Object value) {
    if (!(value instanceof List<?> options)) {
      throw new CandidateIntakeRejectedException("candidate options are missing");
    }
    return options.stream().map(option -> {
      if (option instanceof String text && !text.isBlank()) {
        return text;
      }
      throw new CandidateIntakeRejectedException("candidate option has no id");
    }).toList();
  }

  private static List<String> correctIds(Object value) {
    if (!(value instanceof List<?> correct)) {
      throw new CandidateIntakeRejectedException("candidate answer key is missing");
    }
    return correct.stream().map(valueItem -> {
      if (valueItem instanceof String id) {
        return id;
      }
      throw new CandidateIntakeRejectedException("candidate answer key contains a non-string id");
    }).toList();
  }

  private static Map<String, Object> approvalPayload(
      CandidateContent candidate, Map<String, Object> original) {
    Map<String, Object> payload = new java.util.TreeMap<>();
    payload.putAll(CandidateCanonicalizer.payload(candidate));
    String rationale = nullableString(original, "rationale");
    if (rationale == null || rationale.isBlank()) {
      throw new CandidateIntakeRejectedException("candidate field is missing: rationale");
    }
    payload.put("rationale", rationale);
    return payload;
  }

  public static class CandidateIntakeRejectedException extends RuntimeException {
    public CandidateIntakeRejectedException(String message) { super(message); }
  }

  public static class CandidateIntakeConflictException extends RuntimeException {
    public CandidateIntakeConflictException(String message) { super(message); }
  }
}
