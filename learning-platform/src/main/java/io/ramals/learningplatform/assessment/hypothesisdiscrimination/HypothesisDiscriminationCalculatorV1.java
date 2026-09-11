package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisDrivenProbeDiagnosticSelector;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import io.ramals.learningplatform.assessment.ProbeRelationshipType;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateHypothesis;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateUncertainty;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisEvidenceInput;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code HYPOTHESIS_DISCRIMINATION_V1} (M2-ADR-034 Amendment 2): a deterministic, non-expectation
 * discrimination score per candidate probe -- the total variation distance between two
 * hypothetical {@code HYPOTHESIS_UNCERTAINTY_V1} outcomes. It answers exactly one question --
 * "would this probe's two deterministically reachable outcomes leave the platform in meaningfully
 * different relative-belief states?" -- and nothing about learner correctness, expected
 * information gain, or which outcome is likely (Amendment 2 §A/§D/§I: there is no outcome
 * probability model, and none may be invented).
 *
 * <p><b>Pure.</b> No database, no HTTP, no MCP, no LLM, no clock, no random source. Every
 * dependency is either an argument or {@link HypothesisUncertaintyCalculatorV1}, itself pure.
 *
 * <p><b>Inert.</b> Nothing in {@code io.ramals.learningplatform.assessment} calls this class; no
 * {@code DIAGNOSTIC_SELECTION_V1}-{@code V6} selector reads its result. It computes a score; it
 * does not select or execute a probe (Amendment 2 §O).
 *
 * <h2>Algorithm, exactly as frozen (Amendment 2 §D/§F/§G/§J)</h2>
 *
 * <ol>
 *   <li>Validate the context (§N) -- fail closed, never repaired.
 *   <li>If {@code baseResult.status()} is not {@code APPLICABLE}, return {@code NOT_APPLICABLE}
 *       with no scores (§G) -- no uniform distribution is ever manufactured.
 *   <li>Otherwise, for each candidate probe, in Amendment 2 §L canonical order: a non-scoreable
 *       probe scores exactly {@code 0.0000} (one reachable world, §D); a scoreable probe's score is
 *       the total variation distance between two hypothetical {@code HYPOTHESIS_UNCERTAINTY_V1}
 *       results -- one with a synthetic {@code SUPPORTING} observation for the probe's target
 *       hypothesis, one with a synthetic {@code CONTRADICTORY} observation -- each computed by
 *       calling {@code HYPOTHESIS_UNCERTAINTY_V1.calculate(...)} verbatim, never reimplemented.
 * </ol>
 */
@Component
public class HypothesisDiscriminationCalculatorV1 {

  /** The frozen engine identifier (Amendment 2 §A). Never {@code INFORMATION_GAIN_V1}. */
  public static final String ENGINE_VERSION = "HYPOTHESIS_DISCRIMINATION_V1";

  private static final int OUTPUT_SCALE = 4;
  private static final BigDecimal ZERO_SCALE_4 = BigDecimal.ZERO.setScale(OUTPUT_SCALE);
  private static final BigDecimal TWO = BigDecimal.valueOf(2);

  /** Amendment 1 §H's total order on hypothesis identity, re-derived here (not reused by reference
   * -- {@link HypothesisUncertaintyCalculatorV1}'s own comparator is package-private) from the same
   * frozen source of truth, {@link HypothesisDrivenProbeDiagnosticSelector#RELATIONSHIP_TYPE_PRIORITY}.
   * Identical five-key ordering: relationship-type priority, then {@code targetObjectiveId}, then
   * {@code authorizingRelationshipId} (nulls first), then {@code triggerObjectiveId}, then {@code
   * triggerItemVersionId}. */
  private static final Map<ProbeRelationshipType, Integer> RELATIONSHIP_TYPE_PRIORITY_INDEX =
      new EnumMap<>(ProbeRelationshipType.class);

  static {
    List<ProbeRelationshipType> priority = HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY;
    for (int i = 0; i < priority.size(); i++) {
      RELATIONSHIP_TYPE_PRIORITY_INDEX.put(priority.get(i), i);
    }
  }

  static final Comparator<DiagnosticHypothesis> HYPOTHESIS_CANONICAL_ORDER = Comparator
      .comparingInt((DiagnosticHypothesis h) -> RELATIONSHIP_TYPE_PRIORITY_INDEX.get(h.relationshipType()))
      .thenComparing(h -> h.targetObjectiveId().toString())
      .thenComparing(h -> uuidOrNull(h.authorizingRelationshipId()), Comparator.nullsFirst(Comparator.naturalOrder()))
      .thenComparing(h -> h.triggerObjectiveId().toString())
      .thenComparing(h -> h.triggerItemVersionId().toString());

  /** Amendment 2 §L: canonical emission order for {@link HypothesisDiscriminationResult#probes()}
   * -- the probe's own hypothesis by canonical order, then {@code probeItemVersionId} ascending.
   * Independent of score. */
  private static final Comparator<CandidateProbe> EMISSION_ORDER = Comparator
      .comparing(CandidateProbe::hypothesis, HYPOTHESIS_CANONICAL_ORDER)
      .thenComparing(probe -> probe.probeItemVersionId().toString());

  /** Amendment 2 §K: the separate, frozen ranking contract -- score descending, then hypothesis
   * canonical order ascending, then {@code probeItemVersionId} ascending. Never SQL row order,
   * {@code HashMap}/{@code HashSet} order, input-list order, or UUID randomness. A read-only view
   * over an already-computed {@link HypothesisDiscriminationResult#probes()}; this calculator does
   * not itself select or execute a probe. */
  public static final Comparator<CandidateDiscrimination> RANKING_ORDER = Comparator
      .comparing(CandidateDiscrimination::score, Comparator.reverseOrder())
      .thenComparing(CandidateDiscrimination::hypothesis, HYPOTHESIS_CANONICAL_ORDER)
      .thenComparing(probe -> probe.probeItemVersionId().toString());

  private final HypothesisUncertaintyCalculatorV1 uncertaintyCalculator;

  public HypothesisDiscriminationCalculatorV1(HypothesisUncertaintyCalculatorV1 uncertaintyCalculator) {
    this.uncertaintyCalculator = uncertaintyCalculator;
  }

  /**
   * The pure calculation. See the class javadoc for the algorithm.
   *
   * @throws HypothesisDiscriminationValidationException with a stable reason code if {@code
   *     context} is invalid (Amendment 2 §N) -- never partially computed
   */
  public HypothesisDiscriminationResult calculate(HypothesisDiscriminationContext context) {
    validate(context);

    if (context.baseResult().status() != HypothesisUncertaintyStatus.APPLICABLE) {
      return new HypothesisDiscriminationResult(
          ENGINE_VERSION, HypothesisDiscriminationStatus.NOT_APPLICABLE, List.of());
    }

    List<CandidateProbe> ordered = new ArrayList<>(context.candidates());
    ordered.sort(EMISSION_ORDER);

    List<CandidateDiscrimination> scored = new ArrayList<>(ordered.size());
    for (CandidateProbe probe : ordered) {
      scored.add(new CandidateDiscrimination(probe.probeItemVersionId(), probe.hypothesis(), score(context, probe)));
    }
    return new HypothesisDiscriminationResult(ENGINE_VERSION, HypothesisDiscriminationStatus.SCORABLE, scored);
  }

  // -- scoring: two-world total variation distance (§D) --------------------------------------------

  private BigDecimal score(HypothesisDiscriminationContext context, CandidateProbe probe) {
    if (!probe.scoreable()) {
      // §D: exactly one reachable world (INCONCLUSIVE, which never changes any band) -- there is no
      // second world to differ from, so Score(P) = 0.0000 by construction. No HYPOTHESIS_UNCERTAINTY_V1
      // call is made; there is nothing hypothetical to compute.
      return ZERO_SCALE_4;
    }

    HypothesisUncertaintyContext supportingContext =
        withSyntheticEvidence(context, probe, HypothesisEvidenceOutcome.SUPPORTING);
    HypothesisUncertaintyContext contradictoryContext =
        withSyntheticEvidence(context, probe, HypothesisEvidenceOutcome.CONTRADICTORY);

    HypothesisUncertaintyResult resultSupporting = uncertaintyCalculator.calculate(supportingContext);
    HypothesisUncertaintyResult resultContradictory = uncertaintyCalculator.calculate(contradictoryContext);

    return totalVariationDistance(resultSupporting, resultContradictory);
  }

  private static BigDecimal totalVariationDistance(
      HypothesisUncertaintyResult resultSupporting, HypothesisUncertaintyResult resultContradictory) {
    Map<DiagnosticHypothesis, BigDecimal> supportingValues = valuesByHypothesis(resultSupporting);
    Map<DiagnosticHypothesis, BigDecimal> contradictoryValues = valuesByHypothesis(resultContradictory);

    Set<DiagnosticHypothesis> hypotheses = new HashSet<>(supportingValues.keySet());
    hypotheses.addAll(contradictoryValues.keySet());

    BigDecimal sum = BigDecimal.ZERO;
    for (DiagnosticHypothesis hypothesis : hypotheses) {
      BigDecimal supportingValue = supportingValues.getOrDefault(hypothesis, ZERO_SCALE_4);
      BigDecimal contradictoryValue = contradictoryValues.getOrDefault(hypothesis, ZERO_SCALE_4);
      sum = sum.add(supportingValue.subtract(contradictoryValue).abs());
    }
    return sum.divide(TWO, OUTPUT_SCALE, RoundingMode.HALF_EVEN);
  }

  private static Map<DiagnosticHypothesis, BigDecimal> valuesByHypothesis(HypothesisUncertaintyResult result) {
    Map<DiagnosticHypothesis, BigDecimal> values = new HashMap<>();
    for (CandidateUncertainty candidate : result.candidates()) {
      // §D: a non-participating hypothesis carries no belief mass in either world -- 0.0000, never
      // its own null normalizedValue.
      values.put(candidate.hypothesis(), candidate.participates() ? candidate.normalizedValue() : ZERO_SCALE_4);
    }
    return values;
  }

  // -- synthetic hypothetical context construction (§F) ---------------------------------------------

  private static HypothesisUncertaintyContext withSyntheticEvidence(
      HypothesisDiscriminationContext context, CandidateProbe probe, HypothesisEvidenceOutcome outcome) {
    HypothesisUncertaintyContext baseContext = context.baseContext();
    String domainCode = domainCodeFor(baseContext, probe.hypothesis());

    List<HypothesisEvidenceInput> evidence = new ArrayList<>(baseContext.evidence());
    evidence.add(new HypothesisEvidenceInput(
        syntheticObservationId(probe.probeItemVersionId(), outcome), probe.hypothesis(), outcome,
        baseContext.interactionId(), domainCode));

    return new HypothesisUncertaintyContext(
        baseContext.interactionId(), baseContext.domainCode(), baseContext.candidates(), evidence);
  }

  /** Amendment 2 §F's frozen synthetic-observation-id formula -- deterministic and reproducible
   * identically across JVMs. Never {@code UUID.randomUUID()}, never a timestamp. */
  private static UUID syntheticObservationId(UUID probeItemVersionId, HypothesisEvidenceOutcome outcome) {
    return UUID.nameUUIDFromBytes(
        (probeItemVersionId.toString() + ":" + outcome.name()).getBytes(StandardCharsets.UTF_8));
  }

  private static String domainCodeFor(HypothesisUncertaintyContext baseContext, DiagnosticHypothesis hypothesis) {
    for (CandidateHypothesis candidate : baseContext.candidates()) {
      if (candidate.hypothesis().equals(hypothesis)) {
        return candidate.domainCode();
      }
    }
    // Unreachable once validate() has passed PROBE_FOR_UNKNOWN_HYPOTHESIS -- every probe's
    // hypothesis is already known to be a member of baseContext.candidates() by that point.
    throw new IllegalStateException("hypothesis not found in base context candidates: " + hypothesis);
  }

  // -- validation: fail closed, stable reason codes (§N) ---------------------------------------------

  private void validate(HypothesisDiscriminationContext context) {
    HypothesisUncertaintyResult recomputed = uncertaintyCalculator.calculate(context.baseContext());
    if (!recomputed.equals(context.baseResult())) {
      throw new HypothesisDiscriminationValidationException(
          HypothesisDiscriminationReasonCode.BASE_RESULT_MISMATCH);
    }

    Map<DiagnosticHypothesis, String> domainByHypothesis = new HashMap<>();
    for (CandidateHypothesis candidate : context.baseContext().candidates()) {
      domainByHypothesis.put(candidate.hypothesis(), candidate.domainCode());
    }

    Set<ProbeKey> seen = new HashSet<>();
    for (CandidateProbe probe : context.candidates()) {
      if (probe.probeItemVersionId() == null || probe.hypothesis() == null) {
        throw new HypothesisDiscriminationValidationException(
            HypothesisDiscriminationReasonCode.MALFORMED_CANDIDATE_PROBE);
      }
      String domainCode = domainByHypothesis.get(probe.hypothesis());
      if (domainCode == null) {
        throw new HypothesisDiscriminationValidationException(
            HypothesisDiscriminationReasonCode.PROBE_FOR_UNKNOWN_HYPOTHESIS);
      }
      if (!seen.add(new ProbeKey(probe.probeItemVersionId(), probe.hypothesis()))) {
        throw new HypothesisDiscriminationValidationException(
            HypothesisDiscriminationReasonCode.DUPLICATE_CANDIDATE_PROBE);
      }
      if (!domainCode.equals(context.baseContext().domainCode())) {
        // Defense-in-depth only -- see HypothesisDiscriminationReasonCode#CROSS_DOMAIN_CANDIDATE_PROBE.
        throw new HypothesisDiscriminationValidationException(
            HypothesisDiscriminationReasonCode.CROSS_DOMAIN_CANDIDATE_PROBE);
      }
    }
  }

  private static String uuidOrNull(UUID id) {
    return id == null ? null : id.toString();
  }

  private record ProbeKey(UUID probeItemVersionId, DiagnosticHypothesis hypothesis) {
  }
}
