package io.ramals.learningplatform.assessmentevaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fail-closed Java representation of the M2-T11 assessment-evaluation proposal.
 *
 * <p>The AI payload remains untrusted even though the Python plane validates it first. Runtime-owned
 * identifiers must exactly match the envelope and request that crossed the service boundary; the
 * parser never lets model-controlled values select a different answer, request, run or context.
 */
public record AssessmentEvaluationProposal(
    String contractVersion,
    String proposalId,
    String requestId,
    String agentRunId,
    String contextId,
    String answerVersion,
    String rubricVersion,
    List<Dimension> dimensions,
    String feedback,
    Set<String> evidenceIds,
    BigDecimal confidence) {

  public static final String CONTRACT_VERSION = "1.0";

  private static final int MAX_ID = 64;
  private static final int MAX_DIMENSIONS = 32;
  private static final int MAX_DIMENSION_REASON = 1_000;
  private static final int MAX_FEEDBACK = 4_000;
  private static final int MAX_DIMENSION_EVIDENCE = 32;
  private static final int MAX_FEEDBACK_EVIDENCE = 64;
  private static final int MAX_DECIMAL_LEXICAL_LENGTH = 32;
  private static final int MAX_DECIMAL_PRECISION = 12;
  private static final int MAX_DECIMAL_SCALE = 8;
  private static final int MAX_DECIMAL_INTEGER_DIGITS = 4;
  private static final int MAX_DECIMAL_EXPONENT = 8;
  private static final Pattern DECIMAL_LEXICAL =
      Pattern.compile("[+-]?(\\d+)(?:\\.(\\d+))?(?:[eE]([+-]?\\d+))?");
  private static final Set<String> TOP_LEVEL_FIELDS =
      Set.of(
          "contractVersion",
          "proposalId",
          "requestId",
          "agentRunId",
          "answerVersion",
          "rubricVersion",
          "dimensions",
          "feedback",
          "evidenceIds",
          "confidence");
  private static final Set<String> DIMENSION_FIELDS =
      Set.of("dimensionId", "score", "maxScore", "reason", "evidenceIds");
  private static final Set<String> REQUIRED_TOP_LEVEL_FIELDS =
      Set.of(
          "contractVersion",
          "proposalId",
          "requestId",
          "agentRunId",
          "answerVersion",
          "rubricVersion",
          "dimensions",
          "feedback",
          "confidence");
  private static final Set<String> REQUIRED_DIMENSION_FIELDS =
      Set.of("dimensionId", "score", "maxScore", "reason");

  public AssessmentEvaluationProposal {
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds);
  }

  /** One proposed rubric score and the exact grounded facts offered in support. */
  public record Dimension(
      String dimensionId,
      BigDecimal score,
      BigDecimal maxScore,
      String reason,
      Set<String> evidenceIds) {
    public Dimension {
      evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds);
    }
  }

  /** Trusted identities against which runtime-owned payload fields are checked. */
  public record RuntimeIdentity(
      String proposalId,
      String requestId,
      String agentRunId,
      String contextId,
      String answerVersion,
      String rubricVersion) {}

  /** A stable parser failure safe to persist; raw model content is deliberately excluded. */
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

  /** Parses a model payload and binds it to identities owned by the Spring runtime. */
  public static AssessmentEvaluationProposal parse(
      Map<String, Object> payload, RuntimeIdentity identity) {
    if (payload == null || payload.isEmpty()) {
      throw malformed("EVALUATION_PAYLOAD_ABSENT", "no evaluation proposal payload");
    }
    if (identity == null) {
      throw malformed("EVALUATION_RUNTIME_IDENTITY_INVALID", "runtime identity is absent");
    }
    requireOnlyFields(
        payload,
        TOP_LEVEL_FIELDS,
        REQUIRED_TOP_LEVEL_FIELDS,
        "EVALUATION_PAYLOAD_FIELDS_INVALID");

    String contractVersion =
        bounded(payload.get("contractVersion"), MAX_ID, "EVALUATION_CONTRACT_VERSION_INVALID");
    if (!CONTRACT_VERSION.equals(contractVersion)) {
      throw malformed(
          "EVALUATION_CONTRACT_VERSION_INVALID", "evaluation contract version is unsupported");
    }
    requireRuntimeValue(
        payload, "proposalId", identity.proposalId(), "EVALUATION_PROPOSAL_ID_MISMATCH");
    requireRuntimeValue(
        payload, "requestId", identity.requestId(), "EVALUATION_REQUEST_ID_MISMATCH");
    requireRuntimeValue(
        payload, "agentRunId", identity.agentRunId(), "EVALUATION_AGENT_RUN_ID_MISMATCH");
    requireRuntimeValue(
        payload, "answerVersion", identity.answerVersion(), "EVALUATION_ANSWER_VERSION_MISMATCH");
    requireRuntimeValue(
        payload, "rubricVersion", identity.rubricVersion(), "EVALUATION_RUBRIC_VERSION_MISMATCH");

    Object rawDimensions = payload.get("dimensions");
    if (!(rawDimensions instanceof List<?> entries)
        || entries.isEmpty()
        || entries.size() > MAX_DIMENSIONS) {
      throw malformed(
          "EVALUATION_DIMENSIONS_INVALID", "dimensions must be a bounded, non-empty array");
    }
    List<Dimension> dimensions = new ArrayList<>(entries.size());
    for (Object entry : entries) {
      dimensions.add(parseDimension(entry));
    }

    return new AssessmentEvaluationProposal(
        contractVersion,
        bounded(identity.proposalId(), MAX_ID, "EVALUATION_PROPOSAL_ID_INVALID"),
        bounded(identity.requestId(), MAX_ID, "EVALUATION_REQUEST_ID_INVALID"),
        bounded(identity.agentRunId(), MAX_ID, "EVALUATION_AGENT_RUN_ID_INVALID"),
        bounded(identity.contextId(), MAX_ID, "EVALUATION_CONTEXT_ID_INVALID"),
        bounded(identity.answerVersion(), MAX_ID, "EVALUATION_ANSWER_VERSION_INVALID"),
        bounded(identity.rubricVersion(), MAX_ID, "EVALUATION_RUBRIC_VERSION_INVALID"),
        dimensions,
        bounded(payload.get("feedback"), MAX_FEEDBACK, "EVALUATION_FEEDBACK_INVALID"),
        optionalIdentifiers(
            payload,
            "evidenceIds",
            MAX_FEEDBACK_EVIDENCE,
            "EVALUATION_FEEDBACK_EVIDENCE_INVALID"),
        decimal(
            payload.get("confidence"),
            "EVALUATION_CONFIDENCE_INVALID",
            DecimalDomain.CONFIDENCE));
  }

  private static Dimension parseDimension(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw malformed("EVALUATION_DIMENSION_INVALID", "dimension is not an object");
    }
    requireOnlyFields(
        map,
        DIMENSION_FIELDS,
        REQUIRED_DIMENSION_FIELDS,
        "EVALUATION_DIMENSION_FIELDS_INVALID");
    return new Dimension(
        bounded(map.get("dimensionId"), MAX_ID, "EVALUATION_DIMENSION_ID_INVALID"),
        decimal(map.get("score"), "EVALUATION_SCORE_INVALID", DecimalDomain.NON_NEGATIVE),
        decimal(map.get("maxScore"), "EVALUATION_MAX_SCORE_INVALID", DecimalDomain.POSITIVE),
        bounded(map.get("reason"), MAX_DIMENSION_REASON, "EVALUATION_DIMENSION_REASON_INVALID"),
        optionalIdentifiers(
            map,
            "evidenceIds",
            MAX_DIMENSION_EVIDENCE,
            "EVALUATION_DIMENSION_EVIDENCE_INVALID"));
  }

  private static void requireRuntimeValue(
      Map<String, Object> payload, String field, String expected, String reasonCode) {
    String actual = bounded(payload.get(field), MAX_ID, reasonCode);
    if (!actual.equals(expected)) {
      throw malformed(reasonCode, field + " does not match its runtime-owned value");
    }
  }

  private static Set<String> optionalIdentifiers(
      Map<?, ?> owner, String field, int maximum, String reasonCode) {
    if (!owner.containsKey(field)) {
      return Set.of();
    }
    Object raw = owner.get(field);
    if (!(raw instanceof List<?> list) || list.size() > maximum) {
      throw malformed(reasonCode, "evidence identifiers must be a bounded array");
    }
    Set<String> identifiers = new LinkedHashSet<>();
    for (Object entry : list) {
      if (!identifiers.add(bounded(entry, MAX_ID, reasonCode))) {
        throw malformed(reasonCode, "evidence identifiers must be unique");
      }
    }
    return Set.copyOf(identifiers);
  }

  private static BigDecimal decimal(Object raw, String reasonCode, DecimalDomain domain) {
    if (!(raw instanceof Number)) {
      throw malformed(reasonCode, "a decimal value is required");
    }
    String lexical = raw.toString();
    if (lexical.length() > MAX_DECIMAL_LEXICAL_LENGTH) {
      throw malformed(reasonCode, "decimal lexical length exceeds the resource limit");
    }
    Matcher match = DECIMAL_LEXICAL.matcher(lexical);
    if (!match.matches()) {
      throw malformed(reasonCode, "value is not a finite decimal");
    }
    String integer = match.group(1);
    String fraction = match.group(2) == null ? "" : match.group(2);
    String exponentLexical = match.group(3);
    int exponent = exponentLexical == null ? 0 : boundedExponent(exponentLexical, reasonCode);
    int precision = significantDigits(integer + fraction);
    int scale = fraction.length() - exponent;
    int integerDigits = Math.max(0, integer.replaceFirst("^0+(?!$)", "").length() + exponent);
    if (precision > MAX_DECIMAL_PRECISION
        || scale > MAX_DECIMAL_SCALE
        || integerDigits > MAX_DECIMAL_INTEGER_DIGITS) {
      throw malformed(reasonCode, "decimal precision or scale exceeds the resource limit");
    }
    try {
      BigDecimal value = new BigDecimal(lexical);
      if (!domain.accepts(value)) {
        throw malformed(reasonCode, "decimal is outside the contract range");
      }
      return value;
    } catch (NumberFormatException invalid) {
      throw malformed(reasonCode, "value is not a finite decimal");
    }
  }

  private static int boundedExponent(String lexical, String reasonCode) {
    if (lexical.length() > 3) {
      throw malformed(reasonCode, "decimal exponent exceeds the resource limit");
    }
    int exponent;
    try {
      exponent = Integer.parseInt(lexical);
    } catch (NumberFormatException invalid) {
      throw malformed(reasonCode, "decimal exponent is invalid");
    }
    if (Math.abs(exponent) > MAX_DECIMAL_EXPONENT) {
      throw malformed(reasonCode, "decimal exponent exceeds the resource limit");
    }
    return exponent;
  }

  private static int significantDigits(String digits) {
    String significant = digits.replaceFirst("^0+", "");
    return significant.isEmpty() ? 1 : significant.length();
  }

  private static String bounded(Object raw, int maximum, String reasonCode) {
    if (!(raw instanceof String value) || value.isBlank() || value.length() > maximum) {
      throw malformed(reasonCode, "a bounded, non-blank string is required");
    }
    return value;
  }

  private static void requireOnlyFields(
      Map<?, ?> payload, Set<String> allowed, Set<String> required, String reasonCode) {
    for (Object key : payload.keySet()) {
      if (!(key instanceof String field) || !allowed.contains(field)) {
        throw malformed(reasonCode, "proposal contains an unknown field");
      }
    }
    if (!payload.keySet().containsAll(required)) {
      throw malformed(reasonCode, "proposal is missing a required field");
    }
  }

  private enum DecimalDomain {
    NON_NEGATIVE {
      @Override
      boolean accepts(BigDecimal value) {
        return value.signum() >= 0;
      }
    },
    POSITIVE {
      @Override
      boolean accepts(BigDecimal value) {
        return value.signum() > 0;
      }
    },
    CONFIDENCE {
      @Override
      boolean accepts(BigDecimal value) {
        return value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
      }
    };

    abstract boolean accepts(BigDecimal value);
  }

  private static MalformedProposalException malformed(String code, String message) {
    return new MalformedProposalException(code, message);
  }
}
