package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateDiscrimination;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateProbe;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationContext;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationResult;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationStatus;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateUncertainty;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContextAssembler;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code DIAGNOSTIC_SELECTION_V6} (M2-ADR-034 Amendment 3): the deterministic Step-3 orchestrator
 * that decides, for one attempt being created, whether {@code HYPOTHESIS_DISCRIMINATION_V1}'s
 * ranking may override {@code DIAGNOSTIC_SELECTION_V5}'s own first-eligible tiebreak. It owns
 * Step-3 orchestration semantics only -- candidate authority remains entirely {@code
 * ProbeRelationshipResolver}/{@code ProbeRelationshipService}'s (H4b, unchanged), and Step-1/Step-2
 * mathematics remain entirely {@code HYPOTHESIS_UNCERTAINTY_V1}/{@code HYPOTHESIS_DISCRIMINATION_V1}'s
 * (Amendments 1/2, unchanged, called verbatim, never reimplemented).
 *
 * <p><b>Additive, never a rewrite (Amendment 3 §T).</b> This class discovers no hypothesis and no
 * candidate probe of its own; every one comes from the same existing, already-governed {@code
 * ProbeRelationshipService.resolve} calls {@code DiagnosticService.resolveHypothesisProbeSelection}
 * (V5's own miss-walk) already makes today, walked further rather than stopped at the first hit.
 * When this class does not activate, the caller ({@code DiagnosticService}) falls back to calling
 * that exact, unmodified V5 method -- never a "similar" re-derivation (Amendment 3 §17 of the
 * implementation brief).
 *
 * <p><b>Fail-closed, not fail-soft, for corruption.</b> A {@code
 * HypothesisUncertaintyValidationException} or {@code HypothesisDiscriminationValidationException}
 * from either frozen calculator is never caught here -- it propagates and fails the attempt-creation
 * transaction, exactly as Amendment 3 §N requires. Only the enumerated, expected control outcomes in
 * {@link V6FallbackReason} ever resolve to "use V5's exact selection."
 */
@Service
public class HypothesisDiscriminationDiagnosticSelector {

  /** The frozen policy identifier for Step 3. {@code DIAGNOSTIC_SELECTION_V1}-{@code V5} are
   * untouched and keep their own identifiers. */
  public static final String SELECTION_POLICY_VERSION = "DIAGNOSTIC_SELECTION_V6";

  /**
   * M2-ADR-034 Amendment 3 §E: the frozen hypothesis working-set bound, derived from the one
   * compile-time-fixed cardinality in this feature area --
   * {@link HypothesisDrivenProbeDiagnosticSelector#RELATIONSHIP_TYPE_PRIORITY}'s own length -- never
   * from mutable packet-size configuration. See the ADR for the full five-step derivation. Once this
   * many actionable hypotheses are admitted, working-set admission stops; relationship authority
   * itself, and every other selector, is completely unaffected.
   */
  public static final int MAX_AUTHORIZED_HYPOTHESES_V6 = 4;

  private final AssessmentRepository repository;
  private final ProbeRelationshipService probeRelationshipService;
  private final HypothesisUncertaintyContextAssembler uncertaintyContextAssembler;
  private final HypothesisUncertaintyCalculatorV1 uncertaintyCalculator;
  private final HypothesisDiscriminationCalculatorV1 discriminationCalculator;

  public HypothesisDiscriminationDiagnosticSelector(
      AssessmentRepository repository,
      ProbeRelationshipService probeRelationshipService,
      HypothesisUncertaintyContextAssembler uncertaintyContextAssembler,
      HypothesisUncertaintyCalculatorV1 uncertaintyCalculator,
      HypothesisDiscriminationCalculatorV1 discriminationCalculator) {
    this.repository = repository;
    this.probeRelationshipService = probeRelationshipService;
    this.uncertaintyContextAssembler = uncertaintyContextAssembler;
    this.uncertaintyCalculator = uncertaintyCalculator;
    this.discriminationCalculator = discriminationCalculator;
  }

  /**
   * Attempts a discrimination-driven override of {@code V5}'s own choice for the attempt being
   * created. Returns a {@link Decision} whose {@link Decision#selection()} is present only when
   * every activation condition (Amendment 3 §J) holds; the caller must fall back to calling {@code
   * DiagnosticService.resolveHypothesisProbeSelection} -- unmodified -- whenever it is empty.
   *
   * @throws io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyValidationException
   *     if this class assembles an invalid {@code HypothesisUncertaintyContext} -- a defect in this
   *     class, never caught or masked (Amendment 3 §N)
   * @throws io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationValidationException
   *     if this class assembles an invalid {@code HypothesisDiscriminationContext} -- same fail-closed
   *     discipline
   */
  public Decision select(UUID learnerId, ResolvedDiagnostic diagnostic, List<AdaptiveEligibleItem> unseenPool) {
    Optional<AssessmentAttempt> sourceAttemptOpt =
        repository.findMostRecentCompletedAttempt(learnerId, diagnostic.assessmentVersionId());
    if (sourceAttemptOpt.isEmpty()) {
      return Decision.fallback(null, new WorkingSet(List.of(), List.of(), 0), V6FallbackReason.NO_SOURCE_ATTEMPT);
    }
    AssessmentAttempt sourceAttempt = sourceAttemptOpt.get();

    WorkingSet workingSet = buildWorkingSet(learnerId, sourceAttempt, unseenPool);
    return decide(sourceAttempt.id(), workingSet, unseenPool);
  }

  /**
   * M2-ADR-034 Amendment 4 §O: the same activation/fallback/ranking decision {@link #select} makes
   * from a freshly built {@link WorkingSet}, applied instead to a working set reconstructed from a
   * persisted {@code DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1} snapshot. This is the single
   * authoritative decision computation both the live path and historical replay share -- there is no
   * second, independently written copy of the activation rules for replay to drift from.
   *
   * <p>{@code itemPool} is used only to resolve the winning candidate's {@code targetSkillCode}
   * (item metadata, not an exposure-dependent fact) -- callers replaying a historical decision must
   * pass the destination version's full, unfiltered item pool (e.g. {@code
   * AssessmentRepository#findAdaptiveEligibleItems}), never a learner's current unseen pool, so that
   * replay never reintroduces a live-exposure dependency through this parameter.
   */
  public Decision decideFromPersistedWorkingSet(
      UUID sourceAttemptId, List<DiagnosticHypothesis> actionableHypotheses,
      List<CandidateProbe> candidateProbes, int relationshipAuthorizedCount,
      List<AdaptiveEligibleItem> itemPool) {
    WorkingSet workingSet =
        new WorkingSet(List.copyOf(actionableHypotheses), List.copyOf(candidateProbes), relationshipAuthorizedCount);
    return decide(sourceAttemptId, workingSet, itemPool);
  }

  /**
   * M2-ADR-034 Amendment 4 §K: the exact {@link Decision} {@link #select} produces when no source
   * attempt exists, for replay to reproduce directly, without searching. {@code
   * decideFromPersistedWorkingSet} cannot be reused for this case: called with an empty working set
   * it would reach {@link #decide}'s own {@code NO_ACTIONABLE_HYPOTHESES} branch, which is a
   * genuinely different, ambiguous outcome (a real source attempt that happened to authorize no
   * hypotheses) -- this factory disambiguates the {@code NO_SOURCE_ATTEMPT} case by construction
   * instead of by re-deriving it from an empty list.
   */
  public static Decision noSourceAttemptDecision() {
    return Decision.fallback(null, new WorkingSet(List.of(), List.of(), 0), V6FallbackReason.NO_SOURCE_ATTEMPT);
  }

  private Decision decide(UUID sourceAttemptId, WorkingSet workingSet, List<AdaptiveEligibleItem> unseenPool) {
    if (workingSet.actionableHypotheses.isEmpty()) {
      return Decision.fallback(sourceAttemptId, workingSet, V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);
    }

    // Amendment 3 §P: exactly one possible action anywhere in the working set -- ranking cannot
    // change the outcome. Skip Step 1/Step 2 entirely; no claim is made about what score that sole
    // candidate would receive.
    if (workingSet.candidateProbes.size() == 1) {
      return Decision.fallback(sourceAttemptId, workingSet, V6FallbackReason.SINGLE_CANDIDATE_TOTAL);
    }

    // Amendment 3 §C: source-interaction evidence, never the destination attempt's own id.
    HypothesisUncertaintyContext baseContext =
        uncertaintyContextAssembler.assemble(sourceAttemptId, workingSet.actionableHypotheses);
    HypothesisUncertaintyResult baseResult = uncertaintyCalculator.calculate(baseContext);

    if (baseResult.status() != HypothesisUncertaintyStatus.APPLICABLE) {
      V6FallbackReason reason = baseResult.status() == HypothesisUncertaintyStatus.NOT_APPLICABLE
          ? V6FallbackReason.STEP1_NOT_APPLICABLE
          : V6FallbackReason.STEP1_INSUFFICIENT_EVIDENCE;
      return Decision.afterStep1(sourceAttemptId, workingSet, baseResult, reason);
    }

    long participating = baseResult.candidates().stream().filter(CandidateUncertainty::participates).count();
    if (participating < 2) {
      return Decision.afterStep1(sourceAttemptId, workingSet, baseResult,
          V6FallbackReason.FEWER_THAN_TWO_PARTICIPANTS);
    }

    HypothesisDiscriminationContext discriminationContext =
        new HypothesisDiscriminationContext(baseContext, baseResult, workingSet.candidateProbes);
    HypothesisDiscriminationResult discriminationResult =
        discriminationCalculator.calculate(discriminationContext);

    if (discriminationResult.status() != HypothesisDiscriminationStatus.SCORABLE) {
      return Decision.afterStep2(sourceAttemptId, workingSet, baseResult, discriminationResult,
          null, V6FallbackReason.STEP2_NOT_APPLICABLE);
    }

    BigDecimal maxScore = discriminationResult.probes().stream()
        .map(CandidateDiscrimination::score)
        .max(BigDecimal::compareTo)
        .orElse(BigDecimal.ZERO);

    if (maxScore.compareTo(BigDecimal.ZERO) == 0) {
      return Decision.afterStep2(sourceAttemptId, workingSet, baseResult, discriminationResult,
          maxScore, V6FallbackReason.ALL_SCORES_ZERO);
    }

    // Amendment 3 §Q: Amendment 2 §K's frozen ranking, reused verbatim. No additional term is ever
    // added.
    CandidateDiscrimination winner = discriminationResult.probes().stream()
        .min(HypothesisDiscriminationCalculatorV1.RANKING_ORDER)
        .orElseThrow(() -> new IllegalStateException(
            "SCORABLE result with a positive maxScore must have at least one probe"));

    String targetSkillCode = skillCodeOfItem(unseenPool, winner.probeItemVersionId());
    HypothesisDrivenProbeDiagnosticSelector.Selection selection =
        new HypothesisDrivenProbeDiagnosticSelector.Selection(
            winner.hypothesis(), sourceAttemptId, targetSkillCode, winner.probeItemVersionId());

    return Decision.activated(sourceAttemptId, workingSet, baseResult, discriminationResult,
        maxScore, selection);
  }

  // -- working-set enumeration (Amendment 3 §E/§F/§G/§H/§I) ----------------------------------------

  private WorkingSet buildWorkingSet(
      UUID learnerId, AssessmentAttempt sourceAttempt, List<AdaptiveEligibleItem> unseenPool) {
    List<UUID> misses = repository.findIncorrectItemVersionIdsInPresentationOrder(sourceAttempt.id());

    List<DiagnosticHypothesis> actionableHypotheses = new ArrayList<>();
    List<CandidateProbe> candidateProbes = new ArrayList<>();
    Set<DiagnosticHypothesis> admitted = new HashSet<>();
    int relationshipAuthorizedCount = 0;

    missLoop:
    for (UUID missedItemVersionId : misses) {
      for (ProbeRelationshipType type : HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY) {
        ProbeResolution resolution;
        try {
          resolution = probeRelationshipService.resolve(missedItemVersionId, type, learnerId);
        } catch (TriggerItemHasNoObjectiveException | TriggerItemHasAmbiguousObjectiveException notEligible) {
          // Identical to V5's own semantics (DiagnosticService#resolveHypothesisProbeSelection):
          // this miss is ineligible under every type, so move on to the next miss.
          break;
        }
        if (resolution.outcome() != ProbeResolutionOutcome.CANDIDATES_AVAILABLE) {
          continue;
        }
        relationshipAuthorizedCount++;

        DiagnosticHypothesis hypothesis = resolution.hypothesis();
        if (admitted.contains(hypothesis)) {
          // Exact-identity duplicate of an already-admitted hypothesis -- consumes no slot
          // (Amendment 3 §F).
          continue;
        }

        List<UUID> eligibleItemIds = new ArrayList<>();
        for (ProbeCandidateItem candidate : resolution.candidates()) {
          if (skillCodeOfItem(unseenPool, candidate.itemVersionId()) != null) {
            eligibleItemIds.add(candidate.itemVersionId());
          }
        }
        if (eligibleItemIds.isEmpty()) {
          // Relationship-authorized but not V6-actionable (Amendment 3 §H/§I) -- never enters Step
          // 1, and consumes no working-set slot. The underlying relationship authorization is
          // completely unaffected.
          continue;
        }

        admitted.add(hypothesis);
        actionableHypotheses.add(hypothesis);
        for (UUID itemId : eligibleItemIds) {
          // Every real candidate reaching this point is already verified+scoreable by construction
          // (ProbeRelationshipRepository#itemsForObjective only ever returns scoreable items) --
          // see M2-ADR-034 Amendment 2's own discovery report for this exact finding.
          candidateProbes.add(new CandidateProbe(itemId, hypothesis, true));
        }

        if (actionableHypotheses.size() == MAX_AUTHORIZED_HYPOTHESES_V6) {
          break missLoop;
        }
      }
    }

    return new WorkingSet(actionableHypotheses, candidateProbes, relationshipAuthorizedCount);
  }

  private static String skillCodeOfItem(List<AdaptiveEligibleItem> pool, UUID itemVersionId) {
    for (AdaptiveEligibleItem item : pool) {
      if (item.itemVersionId().equals(itemVersionId)) {
        return item.skillCode();
      }
    }
    return null;
  }

  /** The bounded, de-duplicated, actionability-filtered result of one enumeration walk (Amendment 3
   * §E-§I) -- or, for replay, the identical shape reconstructed from a persisted
   * {@code DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1} snapshot (Amendment 4 §G). Package-visible only
   * for {@link Decision}'s own construction. */
  private record WorkingSet(
      List<DiagnosticHypothesis> actionableHypotheses,
      List<CandidateProbe> candidateProbes,
      int relationshipAuthorizedCount) {
  }

  /**
   * The complete outcome of one {@link #select} (or {@link #decideFromPersistedWorkingSet}) call,
   * carrying both the (possibly absent) resulting
   * {@link HypothesisDrivenProbeDiagnosticSelector.Selection} and the deterministic telemetry this
   * decision is built from (M2-ADR-034 Amendment 3's own implementation brief §22).
   *
   * <p>{@code actionableHypotheses} and {@code candidateProbes} (Amendment 4 §F/§G) are exactly the
   * working set the decision was made from -- the caller (today, {@code DiagnosticService}) persists
   * these verbatim into the {@code DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1} snapshot; this class
   * never persists anything itself.
   *
   * @param selection present iff every activation condition (Amendment 3 §J) held; absent for every
   *     {@link V6FallbackReason}
   * @param sourceAttemptId {@code null} iff {@link V6FallbackReason#NO_SOURCE_ATTEMPT}
   * @param step1Status {@code null} iff Step 1 was never invoked (no source attempt, no actionable
   *     hypotheses, or the single-candidate-total optimization)
   * @param step2Status {@code null} iff Step 2 was never invoked
   * @param maxDiscriminationScore {@code null} iff Step 2 was never invoked
   * @param activated {@code true} iff {@code selection} is present
   * @param fallbackReason {@code null} iff {@code activated}
   */
  public record Decision(
      Optional<HypothesisDrivenProbeDiagnosticSelector.Selection> selection,
      UUID sourceAttemptId,
      int relationshipAuthorizedHypothesisCount,
      int actionableHypothesisCount,
      int candidateProbeCount,
      int participatingHypothesisCount,
      String step1Status,
      String step2Status,
      BigDecimal maxDiscriminationScore,
      boolean activated,
      V6FallbackReason fallbackReason,
      List<DiagnosticHypothesis> actionableHypotheses,
      List<CandidateProbe> candidateProbes) {

    public Decision {
      actionableHypotheses = List.copyOf(actionableHypotheses);
      candidateProbes = List.copyOf(candidateProbes);
    }

    private static Decision fallback(UUID sourceAttemptId, WorkingSet workingSet, V6FallbackReason reason) {
      return new Decision(Optional.empty(), sourceAttemptId, workingSet.relationshipAuthorizedCount(),
          workingSet.actionableHypotheses().size(), workingSet.candidateProbes().size(), 0, null, null,
          null, false, reason, workingSet.actionableHypotheses(), workingSet.candidateProbes());
    }

    private static Decision afterStep1(
        UUID sourceAttemptId, WorkingSet workingSet, HypothesisUncertaintyResult baseResult,
        V6FallbackReason reason) {
      long participating =
          baseResult.candidates().stream().filter(CandidateUncertainty::participates).count();
      return new Decision(Optional.empty(), sourceAttemptId, workingSet.relationshipAuthorizedCount(),
          workingSet.actionableHypotheses().size(), workingSet.candidateProbes().size(),
          (int) participating, baseResult.status().name(), null, null, false, reason,
          workingSet.actionableHypotheses(), workingSet.candidateProbes());
    }

    private static Decision afterStep2(
        UUID sourceAttemptId, WorkingSet workingSet, HypothesisUncertaintyResult baseResult,
        HypothesisDiscriminationResult discriminationResult, BigDecimal maxScore,
        V6FallbackReason reason) {
      long participating =
          baseResult.candidates().stream().filter(CandidateUncertainty::participates).count();
      return new Decision(Optional.empty(), sourceAttemptId, workingSet.relationshipAuthorizedCount(),
          workingSet.actionableHypotheses().size(), workingSet.candidateProbes().size(),
          (int) participating, baseResult.status().name(), discriminationResult.status().name(),
          maxScore, false, reason, workingSet.actionableHypotheses(), workingSet.candidateProbes());
    }

    private static Decision activated(
        UUID sourceAttemptId, WorkingSet workingSet, HypothesisUncertaintyResult baseResult,
        HypothesisDiscriminationResult discriminationResult, BigDecimal maxScore,
        HypothesisDrivenProbeDiagnosticSelector.Selection selection) {
      long participating =
          baseResult.candidates().stream().filter(CandidateUncertainty::participates).count();
      return new Decision(Optional.of(selection), sourceAttemptId,
          workingSet.relationshipAuthorizedCount(), workingSet.actionableHypotheses().size(),
          workingSet.candidateProbes().size(), (int) participating, baseResult.status().name(),
          discriminationResult.status().name(), maxScore, true, null,
          workingSet.actionableHypotheses(), workingSet.candidateProbes());
    }
  }
}
