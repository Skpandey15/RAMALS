package io.ramals.learningplatform.content;

import io.ramals.learningplatform.ai.AssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Receives, validates, and durably records one AI assessment candidate. */
@Service
public class AssessmentCandidateIntakeService {

  private final AssessmentPort assessmentPort;
  private final ContentValidationPipeline validationPipeline;
  private final AssessmentCandidatePersistenceService persistenceService;

  public AssessmentCandidateIntakeService(
      AssessmentPort assessmentPort,
      ContentValidationPipeline validationPipeline,
      AssessmentCandidatePersistenceService persistenceService) {
    this.assessmentPort = assessmentPort;
    this.validationPipeline = validationPipeline;
    this.persistenceService = persistenceService;
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
    return persistenceService.persist(
        candidate, proposal, request, idempotencyActor, idempotencyKey, createdBy);
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


  public static class CandidateIntakeRejectedException extends RuntimeException {
    public CandidateIntakeRejectedException(String message) { super(message); }
  }

  public static class CandidateIntakeConflictException extends RuntimeException {
    public CandidateIntakeConflictException(String message) { super(message); }
  }
}
