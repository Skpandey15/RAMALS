package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceBand;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceInputs;
import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisDrivenProbeDiagnosticSelector;
import io.ramals.learningplatform.assessment.ProbeRelationshipType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code HYPOTHESIS_UNCERTAINTY_V1} (M2-ADR-034 Amendment 1): a deterministic, non-Bayesian,
 * normalized <em>relative</em> hypothesis-uncertainty distribution over an already-authorized bounded
 * candidate set, from per-interaction governed probe evidence only. It answers exactly one question
 * -- "given the hypotheses that currently have directional evidence in this interaction, how is
 * relative corroboration distributed among them?" -- and nothing about ground truth, diagnosis, or
 * what to probe next (Amendment 1 §A).
 *
 * <p><b>Pure.</b> No database, no HTTP, no MCP, no LLM, no clock, no random source. Every dependency
 * is either an argument or {@link DiagnosticConfidenceCalculatorV1}, itself pure. Repository state is
 * converted into a {@link HypothesisUncertaintyContext} by a separate {@link
 * HypothesisUncertaintyContextAssembler} -- this class never queries anything.
 *
 * <p><b>Inert.</b> Nothing in {@code io.ramals.learningplatform.assessment} calls this class; no
 * {@code DIAGNOSTIC_SELECTION_V1}-{@code V5} selector reads its result (an architecture guardrail
 * proves both directions). It computes a belief state; it does not decide the next probe.
 *
 * <h2>Algorithm, exactly as frozen (Amendment 1 §B/§C/§H/§I)</h2>
 *
 * <ol>
 *   <li>Validate the context (§Q) -- fail closed, never repaired.
 *   <li>For each candidate, in canonical order (§H): reduce its evidence to distinct observation ids
 *       (§R), classify via the existing {@link
 *       io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome}, and call {@link
 *       DiagnosticConfidenceCalculatorV1#compute} <b>verbatim</b> -- this class re-implements no
 *       threshold.
 *   <li>Candidates whose band is {@code INSUFFICIENT_EVIDENCE} do not participate (§D); if none
 *       participate, the result is {@link HypothesisUncertaintyStatus#INSUFFICIENT_EVIDENCE} with no
 *       distribution.
 *   <li>Otherwise, weight each participating candidate {@code LOW=1, MODERATE=2, HIGH=3} (§C -- the
 *       complete constant set) and normalize with an intermediate scale-20 {@code HALF_EVEN} division,
 *       a scale-4 {@code DOWN} floor, and a deterministic largest-remainder (Hamilton) residual
 *       allocation tie-broken by the §H canonical order (§I) -- never by collection or database
 *       order.
 * </ol>
 */
@Component
public class HypothesisUncertaintyCalculatorV1 {

  /** The frozen engine identifier (Amendment 1 §A). */
  public static final String ENGINE_VERSION = "HYPOTHESIS_UNCERTAINTY_V1";

  private static final int OUTPUT_SCALE = 4;
  private static final int INTERMEDIATE_SCALE = 20;
  private static final BigDecimal ONE_SCALE_4 = BigDecimal.ONE.setScale(OUTPUT_SCALE);
  private static final BigDecimal RESIDUAL_UNIT = new BigDecimal("0.0001");

  /** Amendment 1 §C -- the complete constant set. {@code INSUFFICIENT_EVIDENCE} has no entry: it is
   * never a point on this scale. */
  private static final Map<DiagnosticConfidenceBand, Integer> BAND_WEIGHT =
      new EnumMap<>(DiagnosticConfidenceBand.class);

  static {
    BAND_WEIGHT.put(DiagnosticConfidenceBand.LOW, 1);
    BAND_WEIGHT.put(DiagnosticConfidenceBand.MODERATE, 2);
    BAND_WEIGHT.put(DiagnosticConfidenceBand.HIGH, 3);
  }

  /** Amendment 1 §H key 1: the frozen relationship-type priority, reused verbatim from V5 -- never
   * redefined here. */
  private static final Map<ProbeRelationshipType, Integer> RELATIONSHIP_TYPE_PRIORITY_INDEX =
      new EnumMap<>(ProbeRelationshipType.class);

  static {
    List<ProbeRelationshipType> priority = HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY;
    for (int i = 0; i < priority.size(); i++) {
      RELATIONSHIP_TYPE_PRIORITY_INDEX.put(priority.get(i), i);
    }
  }

  /** Amendment 1 §H: the total order on {@link DiagnosticHypothesis} identity. Independent of any
   * collection or database order -- see the class-level "H" reference. */
  static final Comparator<DiagnosticHypothesis> CANONICAL_ORDER = Comparator
      .comparingInt((DiagnosticHypothesis h) -> RELATIONSHIP_TYPE_PRIORITY_INDEX.get(h.relationshipType()))
      .thenComparing(h -> h.targetObjectiveId().toString())
      .thenComparing(h -> uuidOrNull(h.authorizingRelationshipId()), Comparator.nullsFirst(Comparator.naturalOrder()))
      .thenComparing(h -> h.triggerObjectiveId().toString())
      .thenComparing(h -> h.triggerItemVersionId().toString());

  private final DiagnosticConfidenceCalculatorV1 confidenceCalculator;

  public HypothesisUncertaintyCalculatorV1(DiagnosticConfidenceCalculatorV1 confidenceCalculator) {
    this.confidenceCalculator = confidenceCalculator;
  }

  /**
   * The pure calculation. See the class javadoc for the algorithm.
   *
   * @throws HypothesisUncertaintyValidationException with a stable reason code if {@code context}
   *     is invalid (Amendment 1 §Q) -- never partially computed
   */
  public HypothesisUncertaintyResult calculate(HypothesisUncertaintyContext context) {
    validate(context);

    List<CandidateHypothesis> ordered = new ArrayList<>(context.candidates());
    ordered.sort(Comparator.comparing(CandidateHypothesis::hypothesis, CANONICAL_ORDER));

    if (ordered.isEmpty()) {
      return new HypothesisUncertaintyResult(ENGINE_VERSION, HypothesisUncertaintyStatus.NOT_APPLICABLE, List.of());
    }

    Map<DiagnosticHypothesis, List<HypothesisEvidenceInput>> evidenceByHypothesis = new HashMap<>();
    for (HypothesisEvidenceInput input : context.evidence()) {
      evidenceByHypothesis.computeIfAbsent(input.hypothesis(), ignored -> new ArrayList<>()).add(input);
    }

    List<CandidateBand> bands = new ArrayList<>(ordered.size());
    for (CandidateHypothesis candidate : ordered) {
      DiagnosticConfidenceBand band = bandFor(
          evidenceByHypothesis.getOrDefault(candidate.hypothesis(), List.of()));
      bands.add(new CandidateBand(candidate.hypothesis(), band));
    }

    List<CandidateBand> participating = bands.stream()
        .filter(candidate -> candidate.band() != DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE)
        .toList();

    if (participating.isEmpty()) {
      List<CandidateUncertainty> unscored = bands.stream()
          .map(candidate -> new CandidateUncertainty(candidate.hypothesis(), candidate.band(), false, null))
          .toList();
      return new HypothesisUncertaintyResult(
          ENGINE_VERSION, HypothesisUncertaintyStatus.INSUFFICIENT_EVIDENCE, unscored);
    }

    Map<DiagnosticHypothesis, BigDecimal> normalized = normalize(participating);
    List<CandidateUncertainty> scored = bands.stream()
        .map(candidate -> {
          boolean participates = candidate.band() != DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE;
          BigDecimal value = participates ? normalized.get(candidate.hypothesis()) : null;
          return new CandidateUncertainty(candidate.hypothesis(), candidate.band(), participates, value);
        })
        .toList();
    return new HypothesisUncertaintyResult(ENGINE_VERSION, HypothesisUncertaintyStatus.APPLICABLE, scored);
  }

  // -- band derivation: reuse DiagnosticConfidenceCalculatorV1 verbatim (§B) -----------------------

  private DiagnosticConfidenceBand bandFor(List<HypothesisEvidenceInput> rawEvidence) {
    // De-duplicate by observation id (§R). A repeat with the SAME outcome is the same observation
    // reaching the assembler twice and is silently folded into one (§J vector 10); a repeat that
    // disagrees on outcome is corrupt input and refuses the whole context -- checked in validate().
    Map<UUID, io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome> distinct = new LinkedHashMap<>();
    for (HypothesisEvidenceInput input : rawEvidence) {
      distinct.putIfAbsent(input.observationId(), input.outcome());
    }

    int supporting = 0;
    int contradictory = 0;
    int inconclusive = 0;
    for (var outcome : distinct.values()) {
      switch (outcome) {
        case SUPPORTING -> supporting++;
        case CONTRADICTORY -> contradictory++;
        case INCONCLUSIVE -> inconclusive++;
      }
    }

    DiagnosticConfidenceInputs inputs;
    try {
      inputs = new DiagnosticConfidenceInputs(supporting, contradictory, inconclusive);
    } catch (IllegalArgumentException negative) {
      // Unreachable via counting (never negative), but this is the second of the two enforcement
      // layers Amendment 1 §Q names for NEGATIVE_EVIDENCE_COUNT.
      throw new HypothesisUncertaintyValidationException(
          HypothesisUncertaintyReasonCode.NEGATIVE_EVIDENCE_COUNT);
    }
    return confidenceCalculator.compute(inputs).band();
  }

  // -- normalization: weights, scale-20 division, floor, largest-remainder residual (§I) ----------

  private Map<DiagnosticHypothesis, BigDecimal> normalize(List<CandidateBand> participating) {
    int total = 0;
    for (CandidateBand candidate : participating) {
      total += BAND_WEIGHT.get(candidate.band());
    }
    BigDecimal totalDecimal = BigDecimal.valueOf(total);

    List<WeightedEntry> entries = new ArrayList<>(participating.size());
    BigDecimal allocated = BigDecimal.ZERO.setScale(OUTPUT_SCALE);
    for (CandidateBand candidate : participating) {
      BigDecimal weight = BigDecimal.valueOf(BAND_WEIGHT.get(candidate.band()));
      BigDecimal exact = weight.divide(totalDecimal, INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
      BigDecimal floor = exact.setScale(OUTPUT_SCALE, RoundingMode.DOWN);
      BigDecimal remainder = exact.subtract(floor);
      entries.add(new WeightedEntry(candidate.hypothesis(), floor, remainder));
      allocated = allocated.add(floor);
    }

    BigDecimal deficit = ONE_SCALE_4.subtract(allocated);
    int unitsToAdd = deficit.divide(RESIDUAL_UNIT, 0, RoundingMode.UNNECESSARY).intValueExact();

    List<WeightedEntry> byRemainderDescendingThenCanonical = new ArrayList<>(entries);
    byRemainderDescendingThenCanonical.sort(
        Comparator.comparing(WeightedEntry::remainder).reversed()
            .thenComparing(WeightedEntry::hypothesis, CANONICAL_ORDER));

    Map<DiagnosticHypothesis, BigDecimal> result = new LinkedHashMap<>();
    for (WeightedEntry entry : entries) {
      result.put(entry.hypothesis(), entry.floor());
    }
    for (int i = 0; i < unitsToAdd; i++) {
      DiagnosticHypothesis hypothesis = byRemainderDescendingThenCanonical.get(i).hypothesis();
      result.put(hypothesis, result.get(hypothesis).add(RESIDUAL_UNIT));
    }
    return result;
  }

  // -- validation: fail closed, stable reason codes (§Q) -------------------------------------------

  private void validate(HypothesisUncertaintyContext context) {
    for (CandidateHypothesis candidate : context.candidates()) {
      requireWellFormed(candidate.hypothesis());
    }

    Set<DiagnosticHypothesis> seen = new HashSet<>();
    Map<DiagnosticHypothesis, String> domainByHypothesis = new HashMap<>();
    for (CandidateHypothesis candidate : context.candidates()) {
      if (!seen.add(candidate.hypothesis())) {
        throw new HypothesisUncertaintyValidationException(
            HypothesisUncertaintyReasonCode.DUPLICATE_HYPOTHESIS);
      }
      if (!candidate.domainCode().equals(context.domainCode())) {
        throw new HypothesisUncertaintyValidationException(
            HypothesisUncertaintyReasonCode.CROSS_DOMAIN_CANDIDATE_SET);
      }
      domainByHypothesis.put(candidate.hypothesis(), candidate.domainCode());
    }

    Map<UUID, HypothesisEvidenceInput> byObservationId = new HashMap<>();
    for (HypothesisEvidenceInput input : context.evidence()) {
      if (!domainByHypothesis.containsKey(input.hypothesis())) {
        throw new HypothesisUncertaintyValidationException(
            HypothesisUncertaintyReasonCode.EVIDENCE_FOR_UNKNOWN_HYPOTHESIS);
      }
      if (!input.interactionId().equals(context.interactionId())) {
        throw new HypothesisUncertaintyValidationException(
            HypothesisUncertaintyReasonCode.EVIDENCE_INTERACTION_MISMATCH);
      }
      if (!input.domainCode().equals(domainByHypothesis.get(input.hypothesis()))) {
        throw new HypothesisUncertaintyValidationException(
            HypothesisUncertaintyReasonCode.CROSS_DOMAIN_EVIDENCE);
      }
      HypothesisEvidenceInput priorObservation = byObservationId.putIfAbsent(input.observationId(), input);
      if (priorObservation != null
          && (priorObservation.outcome() != input.outcome()
              || !priorObservation.hypothesis().equals(input.hypothesis()))) {
        throw new HypothesisUncertaintyValidationException(
            HypothesisUncertaintyReasonCode.DUPLICATE_EVIDENCE_OBSERVATION);
      }
    }
  }

  private static void requireWellFormed(DiagnosticHypothesis hypothesis) {
    if (hypothesis.relationshipType() == null
        || hypothesis.targetObjectiveId() == null
        || hypothesis.triggerObjectiveId() == null
        || hypothesis.triggerItemVersionId() == null) {
      throw new HypothesisUncertaintyValidationException(
          HypothesisUncertaintyReasonCode.MALFORMED_HYPOTHESIS_IDENTITY);
    }
  }

  private static String uuidOrNull(UUID id) {
    return id == null ? null : id.toString();
  }

  private record CandidateBand(DiagnosticHypothesis hypothesis, DiagnosticConfidenceBand band) {
  }

  private record WeightedEntry(DiagnosticHypothesis hypothesis, BigDecimal floor, BigDecimal remainder) {
  }
}
