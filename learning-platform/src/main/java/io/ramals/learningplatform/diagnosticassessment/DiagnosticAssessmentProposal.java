package io.ramals.learningplatform.diagnosticassessment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed MVP-2 diagnostic assessment proposal (M2-T08 contract, M2-T09 gate).
 *
 * <p>Distinct from the MVP-1 diagnostic proposal, which proposes the next probe and whose gate
 * exists partly to refuse any inferred verdict. This one <em>is</em> a verdict, made accountable by
 * mandatory evidence references rather than forbidden. The two share no code and no gate.
 *
 * <p>Parsed from the agent's proposal payload rather than bound directly, because the payload is
 * untrusted input from a model: a field that will not parse is a rejection with a reason code, not
 * an exception thrown out of a deserializer.
 */
public record DiagnosticAssessmentProposal(
    String contractVersion,
    String proposalId,
    String requestId,
    String agentRunId,
    String contextId,
    List<Diagnosis> diagnoses,
    List<String> recommendedNextSkills,
    BigDecimal confidence) {

  /** The wire contract this parser accepts. Anything else fails closed. */
  public static final String CONTRACT_VERSION = "1.0";

  private static final int MAX_DIAGNOSES = 64;
  private static final int MAX_NEXT_SKILLS = 16;
  private static final int MAX_SKILL_CODE = 128;
  private static final int MAX_REASON = 1000;
  private static final int MAX_ID = 64;

  public DiagnosticAssessmentProposal {
    diagnoses = diagnoses == null ? List.of() : List.copyOf(diagnoses);
    recommendedNextSkills =
        recommendedNextSkills == null ? List.of() : List.copyOf(recommendedNextSkills);
  }

  /** One skill-level classification and the evidence it rests on. */
  public record Diagnosis(
      String skillCode, Classification classification, String reason, Set<String> evidenceIds) {
    public Diagnosis {
      evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds);
    }
  }

  /**
   * What the agent believes the evidence shows.
   *
   * <p>Advisory in every case. Deterministic mastery is computed by the domain services from the
   * same evidence and never derives from this value.
   */
  public enum Classification {
    STRONG,
    WEAK,
    INCONSISTENT,
    INSUFFICIENT_EVIDENCE
  }

  /** Raised when a payload cannot be read as this contract at all. */
  public static final class MalformedProposalException extends RuntimeException {
    private final transient String reasonCode;

    MalformedProposalException(String reasonCode, String message) {
      super(message);
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }
  }

  /**
   * Reads an agent payload into this contract, or refuses it.
   *
   * <p>Every bound is checked here rather than trusted from the Python side. The agent validates
   * locally too, and that is deliberate defence in depth: a proposal reaching this method has
   * crossed a network from a service that could be a different version, and "the caller already
   * checked" is not a property this side can verify.
   */
  public static DiagnosticAssessmentProposal parse(
      Map<String, Object> payload,
      String proposalId,
      String requestId,
      String agentRunId,
      String contextId) {
    if (payload == null || payload.isEmpty()) {
      throw new MalformedProposalException("PROPOSAL_PAYLOAD_ABSENT", "no proposal payload");
    }
    Object rawDiagnoses = payload.get("diagnoses");
    if (!(rawDiagnoses instanceof List<?> list) || list.isEmpty() || list.size() > MAX_DIAGNOSES) {
      throw new MalformedProposalException(
          "PROPOSAL_DIAGNOSES_INVALID", "diagnoses must be a bounded, non-empty array");
    }
    List<Diagnosis> parsed = new java.util.ArrayList<>();
    for (Object entry : list) {
      parsed.add(parseDiagnosis(entry));
    }
    return new DiagnosticAssessmentProposal(
        stringOr(payload.get("contractVersion"), CONTRACT_VERSION),
        proposalId,
        requestId,
        agentRunId,
        // Bound by the caller from the context it actually sent, never read from the payload. A
        // model choosing which snapshot its own proposal is judged against would make the freshness
        // check answerable by the thing being checked.
        contextId,
        parsed,
        parseNextSkills(payload.get("recommendedNextSkills")),
        parseConfidence(payload.get("confidence")));
  }

  private static Diagnosis parseDiagnosis(Object entry) {
    if (!(entry instanceof Map<?, ?> map)) {
      throw new MalformedProposalException("PROPOSAL_DIAGNOSIS_INVALID", "diagnosis is not an object");
    }
    String skillCode = bounded(map.get("skillCode"), MAX_SKILL_CODE, "PROPOSAL_SKILL_CODE_INVALID");
    String reason = bounded(map.get("reason"), MAX_REASON, "PROPOSAL_REASON_INVALID");
    Object rawEvidence = map.get("evidenceIds");
    if (!(rawEvidence instanceof List<?> evidence) || evidence.isEmpty()) {
      throw new MalformedProposalException(
          "PROPOSAL_EVIDENCE_INVALID", "a diagnosis must cite at least one evidence identifier");
    }
    Set<String> evidenceIds = new java.util.LinkedHashSet<>();
    for (Object id : evidence) {
      evidenceIds.add(bounded(id, MAX_ID, "PROPOSAL_EVIDENCE_INVALID"));
    }
    // The enum is parsed rather than matched loosely: an unrecognised classification is a proposal
    // this build cannot reason about, and guessing which one was meant would be inventing a verdict.
    Classification classification;
    try {
      classification = Classification.valueOf(String.valueOf(map.get("classification")));
    } catch (IllegalArgumentException | NullPointerException unknown) {
      throw new MalformedProposalException(
          "PROPOSAL_CLASSIFICATION_UNKNOWN", "unrecognised classification");
    }
    return new Diagnosis(skillCode, classification, reason, evidenceIds);
  }

  private static List<String> parseNextSkills(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list) || list.size() > MAX_NEXT_SKILLS) {
      throw new MalformedProposalException(
          "PROPOSAL_RECOMMENDATIONS_INVALID", "recommendedNextSkills must be a bounded array");
    }
    List<String> skills = new java.util.ArrayList<>();
    for (Object entry : list) {
      skills.add(bounded(entry, MAX_SKILL_CODE, "PROPOSAL_RECOMMENDATIONS_INVALID"));
    }
    return List.copyOf(skills);
  }

  private static BigDecimal parseConfidence(Object raw) {
    if (raw == null) {
      throw new MalformedProposalException("PROPOSAL_CONFIDENCE_INVALID", "confidence is required");
    }
    BigDecimal value;
    try {
      value = new BigDecimal(String.valueOf(raw));
    } catch (NumberFormatException notANumber) {
      throw new MalformedProposalException(
          "PROPOSAL_CONFIDENCE_INVALID", "confidence is not a number");
    }
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new MalformedProposalException(
          "PROPOSAL_CONFIDENCE_INVALID", "confidence is outside [0, 1]");
    }
    return value;
  }

  private static String bounded(Object raw, int maxLength, String reasonCode) {
    if (!(raw instanceof String value) || value.isBlank() || value.length() > maxLength) {
      throw new MalformedProposalException(reasonCode, "a bounded, non-blank string is required");
    }
    return value;
  }

  private static String stringOr(Object raw, String fallback) {
    return raw instanceof String value && !value.isBlank() ? value : fallback;
  }
}
