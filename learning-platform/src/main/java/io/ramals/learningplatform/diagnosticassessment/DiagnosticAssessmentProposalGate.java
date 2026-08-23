package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.grounding.GroundedClaim;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextItem;
import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.ProposalGateReason;
import io.ramals.learningplatform.grounding.ProposalGateResult;
import io.ramals.learningplatform.grounding.ProposalGroundingGate;
import io.ramals.learningplatform.grounding.ProposalGroundingRequest;
import io.ramals.learningplatform.grounding.ProposalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic acceptance boundary for MVP-2 diagnostic assessment proposals (M2-T09).
 *
 * <p>Separate from {@code DiagnosticProposalGate}, which is MVP-1's probe gate and is not touched by
 * this class. That gate refuses inferred verdicts; this one accepts them only when they are
 * grounded, consistent and within policy.
 *
 * <p>Composed rather than reimplemented. {@link ProposalGroundingGate} already owns envelope
 * validity, evidence membership, evidence authority, confidence policy and context freshness for
 * every proposal type; duplicating those here would create a second set of rules to drift. What this
 * class adds is what only a diagnostic reading needs: that the skills exist, that the classifications
 * do not contradict each other, and that a strong claim is backed by enough evidence to be one.
 *
 * <p>No model call, no mutable state, no authoritative write. It returns a decision; recording it and
 * acting on it belong to the caller.
 */
public final class DiagnosticAssessmentProposalGate {

  /**
   * Evidence references a STRONG reading must carry.
   *
   * <p>Two rather than one, and deterministic rather than a judgement about the text. A single
   * observation is consistent with a learner having guessed correctly once; the contract's minimum
   * of one reference is what makes a claim *expressible*, and this is what makes a *strong* one
   * defensible. WEAK is deliberately not held to the same bar: proposing that someone needs more
   * practice on thinner evidence is the safe direction to be wrong in.
   */
  static final int STRONG_EVIDENCE_MINIMUM = 2;

  /**
   * The confidence above which a proposal is claiming a conclusion.
   *
   * <p>Used only against INSUFFICIENT_EVIDENCE. "There is not enough evidence to say" and "I am
   * almost certain" are not compatible statements, and a proposal making both has not been honest
   * about one of them.
   */
  static final BigDecimal STRONG_CONCLUSION_CONFIDENCE = new BigDecimal("0.9000");

  /** Fact types whose value names a skill the context describes. */
  private static final String SKILL_CODE_SUFFIX = "SKILL_CODE";

  private final ProposalGroundingGate grounding;

  public DiagnosticAssessmentProposalGate(ProposalGroundingGate grounding) {
    this.grounding = grounding;
  }

  /**
   * Evaluates a proposal against the exact context it was produced from.
   *
   * @param context the context Spring built and sent. Not a context re-retrieved now: a proposal
   *     must be judged against what the agent was actually given, or a concurrent change to learner
   *     state would silently invalidate a proposal that was correct when it was made.
   */
  public ProposalGateResult evaluate(
      DiagnosticAssessmentProposal proposal, GroundedContext context, Instant now) {
    if (proposal == null) {
      return rejected(Set.of(ProposalGateReason.PROPOSAL_INVALID), Set.of());
    }
    if (!DiagnosticAssessmentProposal.CONTRACT_VERSION.equals(proposal.contractVersion())) {
      // Fail closed on an unknown contract version rather than attempting a best-effort read. A
      // version this build does not know is a payload whose meaning it cannot establish.
      return rejected(Set.of(ProposalGateReason.PROPOSAL_VERSION_UNSUPPORTED), Set.of());
    }

    ProposalGateResult grounded = grounding.evaluate(asGroundingRequest(proposal), context, now);

    Set<ProposalGateReason> reasons = new HashSet<>(grounded.reasons());
    reasons.remove(ProposalGateReason.ACCEPTED);
    reasons.addAll(diagnosticReasons(proposal, context));

    if (reasons.isEmpty()) {
      return new ProposalGateResult(
          true, List.of(ProposalGateReason.ACCEPTED), grounded.referencedEvidenceIds());
    }
    return rejected(reasons, grounded.referencedEvidenceIds());
  }

  /**
   * Projects the proposal onto the generic grounding contract.
   *
   * <p>One claim per diagnosis, keyed by the skill it is about. That is what makes the generic gate's
   * "every claim needs permitted authoritative evidence" rule mean "every classification is
   * grounded" here, without the generic gate knowing what a classification is.
   */
  public static ProposalGroundingRequest asGroundingRequest(
      DiagnosticAssessmentProposal proposal) {
    List<GroundedClaim> claims =
        proposal.diagnoses().stream()
            .map(diagnosis -> new GroundedClaim(diagnosis.skillCode(), diagnosis.evidenceIds()))
            .toList();
    return new ProposalGroundingRequest(
        GroundedContext.CONTRACT_VERSION,
        proposal.proposalId(),
        proposal.requestId(),
        proposal.agentRunId(),
        // The context this proposal claims to rest on. The generic gate compares it against the
        // context supplied and rejects a mismatch, which is the freshness/snapshot check.
        contextIdOf(proposal),
        ProposalType.DIAGNOSTIC,
        proposal.confidence(),
        claims);
  }

  private static String contextIdOf(DiagnosticAssessmentProposal proposal) {
    // The agent does not choose the context; Spring sent it and the service binds it to the
    // proposal before the gate runs. Held on the proposal record so the gate needs no second
    // parameter that a caller could pass inconsistently with the context object itself.
    return proposal.contextId();
  }

  private Set<ProposalGateReason> diagnosticReasons(
      DiagnosticAssessmentProposal proposal, GroundedContext context) {
    Set<ProposalGateReason> reasons = new HashSet<>();
    Set<String> knownSkills = skillCodes(context);
    Map<String, DiagnosticAssessmentProposal.Classification> seen = new LinkedHashMap<>();

    if (knownSkills.isEmpty()) {
      reasons.add(ProposalGateReason.SKILL_CONTEXT_MISSING);
    }

    for (DiagnosticAssessmentProposal.Diagnosis diagnosis : proposal.diagnoses()) {
      if (!knownSkills.isEmpty() && !knownSkills.contains(diagnosis.skillCode())) {
        reasons.add(ProposalGateReason.SKILL_NOT_IN_CONTEXT);
      }
      // Any repeat of a skill is refused, whether the classifications agree or not. Normalising a
      // duplicate would mean the gate deciding which reading of a learner is the real one, which is
      // exactly the authority a proposal gate must not take.
      if (seen.put(diagnosis.skillCode(), diagnosis.classification()) != null) {
        reasons.add(ProposalGateReason.CLASSIFICATION_CONFLICT);
      }
      if (diagnosis.classification() == DiagnosticAssessmentProposal.Classification.STRONG
          && countDistinctApplicableLearnerEvidence(diagnosis, context)
              < STRONG_EVIDENCE_MINIMUM) {
        reasons.add(ProposalGateReason.EVIDENCE_INSUFFICIENT_FOR_STRONG);
      }
      if (diagnosis.classification()
              == DiagnosticAssessmentProposal.Classification.INSUFFICIENT_EVIDENCE
          && proposal.confidence().compareTo(STRONG_CONCLUSION_CONFIDENCE) >= 0) {
        reasons.add(ProposalGateReason.INSUFFICIENT_EVIDENCE_OVERCONFIDENT);
      }
    }

    for (String skill : proposal.recommendedNextSkills()) {
      if (!knownSkills.isEmpty() && !knownSkills.contains(skill)) {
        reasons.add(ProposalGateReason.RECOMMENDATION_INVALID);
      }
    }
    return reasons;
  }

  /**
   * Skills the context actually describes, or an empty set when it names none.
   *
   * <p>Empty means "cannot establish membership". The caller fails closed with
   * {@link ProposalGateReason#SKILL_CONTEXT_MISSING}; it never treats absence as permission.
   */
  private static Set<String> skillCodes(GroundedContext context) {
    if (context == null || context.items() == null) {
      return Set.of();
    }
    Set<String> codes = new HashSet<>();
    for (GroundedContextItem item : context.items()) {
      boolean skillBearing =
          item.sourceType() == SourceType.SKILL_GRAPH || item.sourceType() == SourceType.MASTERY;
      if (skillBearing
          && item.authority() == ContextAuthority.AUTHORITATIVE_FACT
          && item.factType() != null
          && item.factType().toUpperCase(Locale.ROOT).endsWith(SKILL_CODE_SUFFIX)
          && item.value() != null) {
        codes.add(String.valueOf(item.value()));
      }
    }
    return codes;
  }

  private static long countDistinctApplicableLearnerEvidence(
      DiagnosticAssessmentProposal.Diagnosis diagnosis, GroundedContext context) {
    if (context == null || context.items() == null) {
      return 0;
    }
    Map<String, GroundedContextItem> supplied = new java.util.HashMap<>();
    context.items().forEach(item -> supplied.putIfAbsent(item.evidenceId(), item));
    return diagnosis.evidenceIds().stream()
        .distinct()
        .map(supplied::get)
        .filter(DiagnosticAssessmentProposalGate::isAuthoritativeLearnerPerformanceEvidence)
        .filter(item -> appliesToSkillWhenKnown(item, diagnosis.skillCode()))
        .count();
  }

  private static boolean isAuthoritativeLearnerPerformanceEvidence(GroundedContextItem item) {
    return item != null
        && item.authority() == ContextAuthority.AUTHORITATIVE_FACT
        && (item.sourceType() == SourceType.LEARNER_EVIDENCE
            || item.sourceType() == SourceType.MASTERY);
  }

  /**
   * An item without skill metadata remains usable because today's transport carries scalar facts
   * whose learner-evidence and mastery rows do not expose skill code. When the fact explicitly names
   * a skill, however, it must name the diagnosed one.
   */
  private static boolean appliesToSkillWhenKnown(GroundedContextItem item, String diagnosedSkill) {
    if (item.factType() == null
        || !item.factType().toUpperCase(Locale.ROOT).endsWith(SKILL_CODE_SUFFIX)) {
      return true;
    }
    return item.value() != null && diagnosedSkill.equals(String.valueOf(item.value()));
  }

  private static ProposalGateResult rejected(
      Set<ProposalGateReason> reasons, Set<String> referenced) {
    List<ProposalGateReason> ordered = new ArrayList<>(reasons);
    ordered.sort(Comparator.comparing(Enum::name));
    return new ProposalGateResult(false, ordered, referenced);
  }
}
