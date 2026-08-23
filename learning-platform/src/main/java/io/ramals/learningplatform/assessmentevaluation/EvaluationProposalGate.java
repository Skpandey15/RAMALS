package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.ai.contract.AssessmentEvaluationContext;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationRequest;
import io.ramals.learningplatform.ai.contract.AssessmentRubricDimension;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextItem;
import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.GroundedContextValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spring-owned deterministic acceptance boundary for AI-assisted rubric evaluation (M2-T12).
 *
 * <p>The gate is pure and has no persistence or model dependency. It validates the exact answer and
 * approved rubric that Spring sent, the grounded facts that support every score and feedback claim,
 * bounded rubric math, policy, confidence, and any deterministic comparison supplied by the core.
 * Only {@link Outcome#ACCEPTED} permits a later authoritative service to create evidence.
 */
public final class EvaluationProposalGate {

  public static final String POLICY_VERSION = "EVALUATION_GATE_V1";
  public static final String REQUEST_POLICY = "EVALUATION_POLICY_V1";
  static final BigDecimal MINIMUM_CONFIDENCE = new BigDecimal("0.7000");

  private static final Pattern AUTHORITY_CLAIM =
      Pattern.compile(
          "\\b(final score|official score|mastery (?:was|is)|progression (?:was|is)|"
              + "has been (?:recorded|committed|saved)|(?:pass|fail)(?:ed)? the assessment)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern STABLE_REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  private final GroundedContextValidator contextValidator;

  public EvaluationProposalGate(GroundedContextValidator contextValidator) {
    this.contextValidator = contextValidator;
  }

  /** The three policy outcomes; only ACCEPTED can feed an authoritative effect. */
  public enum Outcome {
    ACCEPTED,
    REJECTED,
    MANUAL_REVIEW;

    public boolean allowsAuthoritativeEffect() {
      return this == ACCEPTED;
    }
  }

  /** Stable, platform-authored reason codes. Model prose is never used as a reason code. */
  public enum Reason {
    ACCEPTED,
    PROPOSAL_INVALID,
    PROPOSAL_VERSION_UNSUPPORTED,
    REQUEST_ID_MISMATCH,
    REQUEST_POLICY_INVALID,
    CONTEXT_ID_MISMATCH,
    GROUNDING_INVALID,
    ANSWER_VERSION_MISMATCH,
    RUBRIC_VERSION_MISMATCH,
    ANSWER_NOT_GROUNDED,
    RUBRIC_NOT_GROUNDED,
    RUBRIC_CONFIGURATION_INVALID,
    RUBRIC_DIMENSIONS_MISMATCH,
    RUBRIC_DIMENSION_DUPLICATE,
    RUBRIC_MAX_SCORE_MISMATCH,
    RUBRIC_SCORE_OUT_OF_RANGE,
    EVIDENCE_REFERENCE_UNKNOWN,
    EVIDENCE_REFERENCE_NON_AUTHORITATIVE,
    DIMENSION_EVIDENCE_INCOMPLETE,
    FEEDBACK_EVIDENCE_INCOMPLETE,
    POLICY_AUTHORITY_CLAIM,
    CONFIDENCE_BELOW_POLICY,
    DETERMINISTIC_CONFLICT,
    ENVELOPE_AGENT_INVALID,
    ENVELOPE_TRUST_INVALID
  }

  /** Comparison produced by deterministic core logic, never inferred from model prose. */
  public record DeterministicCheck(Comparison comparison, String reasonCode) {
    public DeterministicCheck {
      if (comparison == null) {
        throw new IllegalArgumentException("deterministic comparison is required");
      }
      if (reasonCode != null && !STABLE_REASON_CODE.matcher(reasonCode).matches()) {
        throw new IllegalArgumentException("deterministic reasonCode must be a stable code");
      }
      if (comparison == Comparison.CONFLICTS && reasonCode == null) {
        throw new IllegalArgumentException("a deterministic conflict requires a reasonCode");
      }
    }

    public enum Comparison {
      NOT_APPLICABLE,
      AGREES,
      CONFLICTS
    }

    public static DeterministicCheck notApplicable() {
      return new DeterministicCheck(Comparison.NOT_APPLICABLE, null);
    }

    public static DeterministicCheck agrees(String reasonCode) {
      return new DeterministicCheck(Comparison.AGREES, reasonCode);
    }

    public static DeterministicCheck conflicts(String reasonCode) {
      return new DeterministicCheck(Comparison.CONFLICTS, reasonCode);
    }
  }

  /** Canonical rubric result retained for traceability and approved feedback reads. */
  public record DimensionResult(
      String dimensionId,
      BigDecimal score,
      BigDecimal maxScore,
      String reason,
      Set<String> evidenceIds) {
    public DimensionResult {
      evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds);
    }
  }

  /** Complete deterministic outcome ready for immutable persistence. */
  public record Decision(
      Outcome outcome,
      List<Reason> reasons,
      Set<String> referencedEvidenceIds,
      List<DimensionResult> dimensions,
      String feedback,
      BigDecimal confidence,
      DeterministicCheck deterministicCheck) {
    public Decision {
      reasons = List.copyOf(reasons);
      referencedEvidenceIds = Set.copyOf(referencedEvidenceIds);
      dimensions = List.copyOf(dimensions);
    }

    public boolean allowsAuthoritativeEffect() {
      return outcome.allowsAuthoritativeEffect();
    }
  }

  /** Evaluates one proposal against the exact request/context from which it was produced. */
  public Decision evaluate(
      AssessmentEvaluationProposal proposal,
      AssessmentEvaluationRequest request,
      DeterministicCheck deterministicCheck,
      Instant now) {
    DeterministicCheck comparison =
        deterministicCheck == null ? DeterministicCheck.notApplicable() : deterministicCheck;
    if (proposal == null || request == null || now == null) {
      return decision(Outcome.REJECTED, Set.of(Reason.PROPOSAL_INVALID), proposal, comparison);
    }
    if (!AssessmentEvaluationProposal.CONTRACT_VERSION.equals(proposal.contractVersion())) {
      return decision(
          Outcome.REJECTED,
          Set.of(Reason.PROPOSAL_VERSION_UNSUPPORTED),
          proposal,
          comparison);
    }

    Set<Reason> reasons = new HashSet<>();
    validateRequestPolicy(request, reasons);
    AssessmentEvaluationContext evaluation = request.evaluationContext();
    GroundedContext context = request.groundedContext();
    if (evaluation == null || context == null) {
      reasons.add(Reason.PROPOSAL_INVALID);
      return decision(Outcome.REJECTED, reasons, proposal, comparison);
    }
    validateRuntimeIdentity(proposal, request, reasons);
    validateEvaluationContext(evaluation, reasons);
    if (!Objects.equals(proposal.contextId(), context.contextId())) {
      reasons.add(Reason.CONTEXT_ID_MISMATCH);
      return decision(Outcome.REJECTED, withConflict(reasons, comparison), proposal, comparison);
    }

    Map<String, GroundedContextItem> authoritative = validateGrounding(context, now, reasons);
    Map<String, AssessmentRubricDimension> configured = configuredDimensions(evaluation, reasons);
    validateVersions(proposal, evaluation, reasons);
    validateAnswerGrounding(evaluation, authoritative, reasons);
    validateRubricGrounding(evaluation, configured, authoritative, reasons);
    validateProposalDimensions(proposal, evaluation, configured, authoritative, reasons);
    validateFeedback(proposal, evaluation, authoritative, reasons);

    if (proposal.confidence() == null
        || proposal.confidence().signum() < 0
        || proposal.confidence().compareTo(BigDecimal.ONE) > 0) {
      reasons.add(Reason.PROPOSAL_INVALID);
    } else if (proposal.confidence().compareTo(MINIMUM_CONFIDENCE) < 0) {
      reasons.add(Reason.CONFIDENCE_BELOW_POLICY);
    }
    reasons = withConflict(reasons, comparison);

    boolean hardRejection =
        reasons.stream()
            .anyMatch(
                reason ->
                    reason != Reason.CONFIDENCE_BELOW_POLICY
                        && reason != Reason.DETERMINISTIC_CONFLICT);
    if (hardRejection) {
      return decision(Outcome.REJECTED, reasons, proposal, comparison);
    }
    if (!reasons.isEmpty()) {
      return decision(Outcome.MANUAL_REVIEW, reasons, proposal, comparison);
    }
    return decision(Outcome.ACCEPTED, Set.of(Reason.ACCEPTED), proposal, comparison);
  }

  /** Builds a rejection for envelope/parser failures that happened before a proposal existed. */
  public Decision rejectBeforeParse(Reason reason, DeterministicCheck deterministicCheck) {
    if (reason == null || reason == Reason.ACCEPTED) {
      throw new IllegalArgumentException("a rejection reason is required");
    }
    DeterministicCheck comparison =
        deterministicCheck == null ? DeterministicCheck.notApplicable() : deterministicCheck;
    return new Decision(
        Outcome.REJECTED,
        ordered(withConflict(Set.of(reason), comparison)),
        Set.of(),
        List.of(),
        null,
        null,
        comparison);
  }

  private static void validateRequestPolicy(
      AssessmentEvaluationRequest request, Set<Reason> reasons) {
    if (!AssessmentEvaluationRequest.CONTRACT_VERSION.equals(request.contractVersion())
        || request.constraints() == null
        || request.constraints().interactionClass() != InteractionClass.ASSESSMENT_PROPOSAL
        || !REQUEST_POLICY.equals(request.constraints().policyVersion())
        || request.groundedContext() == null
        || !REQUEST_POLICY.equals(
            request.groundedContext().retrievalPolicyVersion())) {
      reasons.add(Reason.REQUEST_POLICY_INVALID);
    }
  }

  private static void validateRuntimeIdentity(
      AssessmentEvaluationProposal proposal,
      AssessmentEvaluationRequest request,
      Set<Reason> reasons) {
    if (!bounded(proposal.proposalId())
        || !bounded(proposal.agentRunId())
        || !bounded(proposal.requestId())
        || !bounded(proposal.contextId())) {
      reasons.add(Reason.PROPOSAL_INVALID);
    }
    if (!Objects.equals(proposal.requestId(), request.requestId())) {
      reasons.add(Reason.REQUEST_ID_MISMATCH);
    }
  }

  private static void validateEvaluationContext(
      AssessmentEvaluationContext evaluation, Set<Reason> reasons) {
    if (evaluation.responseType() == null
        || !bounded(evaluation.answerVersion())
        || !bounded(evaluation.rubricVersion())
        || !bounded(evaluation.answerEvidenceId())
        || evaluation.answerText() == null
        || evaluation.answerText().isBlank()
        || evaluation.answerText().length() > 12_000) {
      reasons.add(Reason.REQUEST_POLICY_INVALID);
    }
  }

  private Map<String, GroundedContextItem> validateGrounding(
      GroundedContext context, Instant now, Set<Reason> reasons) {
    try {
      contextValidator.validate(context, Set.of(SourceType.ASSESSMENT), now);
    } catch (GroundedContextValidator.GroundedContextException invalid) {
      reasons.add(Reason.GROUNDING_INVALID);
      return Map.of();
    }
    Map<String, GroundedContextItem> authoritative = new HashMap<>();
    for (GroundedContextItem item : context.items()) {
      if (authoritative.putIfAbsent(item.evidenceId(), item) != null) {
        reasons.add(Reason.GROUNDING_INVALID);
      }
    }
    return authoritative;
  }

  private static Map<String, AssessmentRubricDimension> configuredDimensions(
      AssessmentEvaluationContext evaluation, Set<Reason> reasons) {
    if (evaluation.rubricDimensions() == null
        || evaluation.rubricDimensions().isEmpty()
        || evaluation.rubricDimensions().size() > 32) {
      reasons.add(Reason.RUBRIC_CONFIGURATION_INVALID);
      return Map.of();
    }
    Map<String, AssessmentRubricDimension> configured = new LinkedHashMap<>();
    for (AssessmentRubricDimension dimension : evaluation.rubricDimensions()) {
      if (dimension == null
          || !bounded(dimension.dimensionId())
          || dimension.maxScore() == null
          || dimension.maxScore().signum() <= 0
          || dimension.maxScore().compareTo(new BigDecimal("1000")) > 0
          || !bounded(dimension.evidenceId())
          || dimension.criteria() == null
          || dimension.criteria().isBlank()
          || dimension.criteria().length() > 1_000
          || configured.putIfAbsent(dimension.dimensionId(), dimension) != null) {
        reasons.add(Reason.RUBRIC_CONFIGURATION_INVALID);
      }
    }
    return configured;
  }

  private static void validateVersions(
      AssessmentEvaluationProposal proposal,
      AssessmentEvaluationContext evaluation,
      Set<Reason> reasons) {
    if (!Objects.equals(proposal.answerVersion(), evaluation.answerVersion())) {
      reasons.add(Reason.ANSWER_VERSION_MISMATCH);
    }
    if (!Objects.equals(proposal.rubricVersion(), evaluation.rubricVersion())) {
      reasons.add(Reason.RUBRIC_VERSION_MISMATCH);
    }
  }

  private static void validateAnswerGrounding(
      AssessmentEvaluationContext evaluation,
      Map<String, GroundedContextItem> authoritative,
      Set<Reason> reasons) {
    GroundedContextItem answer = authoritative.get(evaluation.answerEvidenceId());
    if (!matches(
        answer,
        SourceType.ASSESSMENT,
        evaluation.answerVersion(),
        "ANSWER_VERSION",
        evaluation.answerVersion())) {
      reasons.add(Reason.ANSWER_NOT_GROUNDED);
    }
  }

  private static void validateRubricGrounding(
      AssessmentEvaluationContext evaluation,
      Map<String, AssessmentRubricDimension> configured,
      Map<String, GroundedContextItem> authoritative,
      Set<Reason> reasons) {
    for (AssessmentRubricDimension dimension : configured.values()) {
      GroundedContextItem rubric = authoritative.get(dimension.evidenceId());
      if (!matches(
          rubric,
          SourceType.ASSESSMENT,
          evaluation.rubricVersion(),
          "RUBRIC_DIMENSION",
          dimension.dimensionId())) {
        reasons.add(Reason.RUBRIC_NOT_GROUNDED);
      }
    }
  }

  private static void validateProposalDimensions(
      AssessmentEvaluationProposal proposal,
      AssessmentEvaluationContext evaluation,
      Map<String, AssessmentRubricDimension> configured,
      Map<String, GroundedContextItem> authoritative,
      Set<Reason> reasons) {
    Map<String, AssessmentEvaluationProposal.Dimension> proposed = new LinkedHashMap<>();
    for (AssessmentEvaluationProposal.Dimension dimension : proposal.dimensions()) {
      if (dimension == null
          || !bounded(dimension.dimensionId())
          || dimension.reason() == null
          || dimension.reason().isBlank()
          || dimension.reason().length() > 1_000
          || dimension.evidenceIds().isEmpty()) {
        reasons.add(Reason.PROPOSAL_INVALID);
        continue;
      }
      if (proposed.putIfAbsent(dimension.dimensionId(), dimension) != null) {
        reasons.add(Reason.RUBRIC_DIMENSION_DUPLICATE);
      }
    }
    if (!proposed.keySet().equals(configured.keySet())) {
      reasons.add(Reason.RUBRIC_DIMENSIONS_MISMATCH);
    }

    for (Map.Entry<String, AssessmentEvaluationProposal.Dimension> entry : proposed.entrySet()) {
      AssessmentRubricDimension expected = configured.get(entry.getKey());
      AssessmentEvaluationProposal.Dimension actual = entry.getValue();
      if (expected == null) {
        continue;
      }
      if (actual.maxScore() == null
          || actual.maxScore().compareTo(expected.maxScore()) != 0) {
        reasons.add(Reason.RUBRIC_MAX_SCORE_MISMATCH);
      }
      if (actual.score() == null
          || actual.score().signum() < 0
          || actual.score().compareTo(expected.maxScore()) > 0) {
        reasons.add(Reason.RUBRIC_SCORE_OUT_OF_RANGE);
      }
      validateEvidenceReferences(actual.evidenceIds(), authoritative, reasons);
      if (!actual.evidenceIds().contains(evaluation.answerEvidenceId())
          || !actual.evidenceIds().contains(expected.evidenceId())) {
        reasons.add(Reason.DIMENSION_EVIDENCE_INCOMPLETE);
      }
      if (actual.reason() != null && AUTHORITY_CLAIM.matcher(actual.reason()).find()) {
        reasons.add(Reason.POLICY_AUTHORITY_CLAIM);
      }
    }
  }

  private static void validateFeedback(
      AssessmentEvaluationProposal proposal,
      AssessmentEvaluationContext evaluation,
      Map<String, GroundedContextItem> authoritative,
      Set<Reason> reasons) {
    validateEvidenceReferences(proposal.evidenceIds(), authoritative, reasons);
    if (!proposal.evidenceIds().contains(evaluation.answerEvidenceId())) {
      reasons.add(Reason.FEEDBACK_EVIDENCE_INCOMPLETE);
    }
    if (proposal.feedback() == null
        || proposal.feedback().isBlank()
        || proposal.feedback().length() > 4_000) {
      reasons.add(Reason.PROPOSAL_INVALID);
    } else if (AUTHORITY_CLAIM.matcher(proposal.feedback()).find()) {
      reasons.add(Reason.POLICY_AUTHORITY_CLAIM);
    }
  }

  private static void validateEvidenceReferences(
      Set<String> evidenceIds,
      Map<String, GroundedContextItem> authoritative,
      Set<Reason> reasons) {
    for (String evidenceId : evidenceIds) {
      GroundedContextItem item = authoritative.get(evidenceId);
      if (item == null) {
        reasons.add(Reason.EVIDENCE_REFERENCE_UNKNOWN);
      } else if (item.authority() != ContextAuthority.AUTHORITATIVE_FACT) {
        reasons.add(Reason.EVIDENCE_REFERENCE_NON_AUTHORITATIVE);
      }
    }
  }

  private static boolean matches(
      GroundedContextItem item,
      SourceType source,
      String sourceVersion,
      String factType,
      String value) {
    return item != null
        && item.sourceType() == source
        && item.authority() == ContextAuthority.AUTHORITATIVE_FACT
        && sourceVersion.equals(item.sourceVersion())
        && factType.equals(item.factType())
        && value.equals(String.valueOf(item.value()));
  }

  private static boolean bounded(String value) {
    return value != null && !value.isBlank() && value.length() <= 64;
  }

  private static Set<Reason> withConflict(
      Set<Reason> reasons, DeterministicCheck deterministicCheck) {
    Set<Reason> combined = new HashSet<>(reasons);
    if (deterministicCheck.comparison() == DeterministicCheck.Comparison.CONFLICTS) {
      combined.add(Reason.DETERMINISTIC_CONFLICT);
    }
    return combined;
  }

  private static Decision decision(
      Outcome outcome,
      Set<Reason> reasons,
      AssessmentEvaluationProposal proposal,
      DeterministicCheck deterministicCheck) {
    if (proposal == null) {
      return new Decision(
          outcome,
          ordered(reasons),
          Set.of(),
          List.of(),
          null,
          null,
          deterministicCheck);
    }
    Set<String> referenced = new HashSet<>(proposal.evidenceIds());
    proposal.dimensions().forEach(dimension -> referenced.addAll(dimension.evidenceIds()));
    List<DimensionResult> dimensions =
        proposal.dimensions().stream()
            .filter(dimension -> dimension != null && bounded(dimension.dimensionId()))
            .map(
                dimension ->
                    new DimensionResult(
                        dimension.dimensionId(),
                        dimension.score(),
                        dimension.maxScore(),
                        dimension.reason(),
                        dimension.evidenceIds()))
            .sorted(Comparator.comparing(DimensionResult::dimensionId))
            .toList();
    return new Decision(
        outcome,
        ordered(reasons),
        referenced,
        dimensions,
        proposal.feedback(),
        proposal.confidence(),
        deterministicCheck);
  }

  private static List<Reason> ordered(Set<Reason> reasons) {
    List<Reason> ordered = new ArrayList<>(reasons);
    ordered.sort(Comparator.comparing(reason -> reason.name().toUpperCase(Locale.ROOT)));
    return List.copyOf(ordered);
  }
}
