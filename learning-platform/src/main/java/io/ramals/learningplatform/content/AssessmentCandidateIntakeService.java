package io.ramals.learningplatform.content;

import io.ramals.learningplatform.ai.AssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Receives, validates, and durably records one AI assessment candidate. */
@Service
public class AssessmentCandidateIntakeService {

  private static final Logger LOGGER = LoggerFactory.getLogger(AssessmentCandidateIntakeService.class);

  private final AssessmentPort assessmentPort;
  private final ContentValidationPipeline validationPipeline;
  private final AssessmentCandidatePersistenceService persistenceService;
  private final AiExecutionRecorder executionRecorder;

  // There is deliberately no constructor that omits the recorder. One existed, defaulting to a
  // no-op, and only @Autowired on the constructor below kept production wired to the real one --
  // remove that annotation and the platform still starts, still serves, and silently stops
  // recording the AI execution provenance M1-ADR-005 requires, with nothing failing.
  public AssessmentCandidateIntakeService(
      AssessmentPort assessmentPort,
      ContentValidationPipeline validationPipeline,
      AssessmentCandidatePersistenceService persistenceService,
      AiExecutionRecorder executionRecorder) {
    this.assessmentPort = assessmentPort;
    this.validationPipeline = validationPipeline;
    this.persistenceService = persistenceService;
    this.executionRecorder = executionRecorder;
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
    Instant executionStarted = Instant.now();
    AiExecutionCommission commission = executionRecorder.commission(request, "ASSESSMENT");
    if (!commission.dispatchAllowed()) {
      if (commission.existingExecution().isPresent()) {
        throw new AiExecutionAlreadyCompletedException(
            "AI execution already completed for requestId: " + request.requestId());
      }
      throw new AiExecutionInProgressException(
          "AI execution is already in progress for requestId: " + request.requestId());
    }
    AiProposalEnvelope proposal;
    try {
      proposal = assessmentPort.requestAssessmentProposal(request, deadlineMillis, requestedDifficulty);
    } catch (RuntimeException failure) {
      try {
        executionRecorder.recordFailure(request, "ASSESSMENT", errorCode(failure), executionStarted, Instant.now());
      } catch (RuntimeException persistenceFailure) {
        failure.addSuppressed(persistenceFailure);
      }
      throw failure;
    }
    executionRecorder.recordSuccess(request, proposal, executionStarted, Instant.now());
    CandidateContent candidate = candidateFrom(proposal, assessmentVersionId);
    ContentValidationPipeline.Outcome outcome = validationPipeline.validate(candidate, validationContext);
    if (outcome.rejected()) {
      throw new CandidateIntakeRejectedException(((ContentValidationPipeline.Outcome.Rejected) outcome).reason());
    }
    if (proposal.agentType() != AgentType.ASSESSMENT || proposal.trustLevel() != TrustLevel.UNVERIFIED) {
      throw new CandidateIntakeRejectedException("proposal is not an UNVERIFIED assessment candidate");
    }
    AssessmentCandidateRevision persisted = persistenceService.persist(
        candidate, proposal, request, idempotencyActor, idempotencyKey, createdBy);
    BusinessEventLogger.info(LOGGER, "content.candidate.generated",
        "Assessment candidate generated and persisted",
        Map.of("entityType", "ASSESSMENT_CANDIDATE_REVISION", "entityId", persisted.candidateId(),
            "candidateRevision", persisted.candidateRevision(), "assessmentVersionId", assessmentVersionId,
            "outcome", "SUCCESS"));
    return persisted;
  }

  private static String errorCode(RuntimeException failure) {
    if (failure instanceof io.ramals.learningplatform.ai.AiUnavailableException unavailable) {
      return unavailable.code();
    }
    return "AI_EXECUTION_FAILURE";
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

  public static class AiExecutionAlreadyCompletedException extends RuntimeException {
    public AiExecutionAlreadyCompletedException(String message) { super(message); }
  }

  public static class AiExecutionInProgressException extends RuntimeException {
    public AiExecutionInProgressException(String message) { super(message); }
  }
}
