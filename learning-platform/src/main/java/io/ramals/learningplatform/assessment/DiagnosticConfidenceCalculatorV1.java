package io.ramals.learningplatform.assessment;

import org.springframework.stereotype.Component;

/**
 * DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-023 §2): the deterministic, staged rule turning distinct evidence
 * observation counts for one hypothesis tuple (learner + source objective + target objective +
 * relationship type) into a {@link DiagnosticConfidenceBand}. Pure -- no database access, the same
 * discipline {@code PrerequisiteAwareDiagnosticSelector}/{@code HypothesisDrivenProbeDiagnosticSelector}
 * are already held to; a call with the same {@link DiagnosticConfidenceInputs} always produces the
 * same {@link DiagnosticConfidenceResult}.
 *
 * <pre>
 *   s = supportingCount, c = contradictoryCount
 *
 *   s == 0 &amp;&amp; c == 0        -&gt; INSUFFICIENT_EVIDENCE
 *   c == 0 &amp;&amp; s == 1        -&gt; LOW
 *   c == 0 &amp;&amp; s == 2        -&gt; MODERATE
 *   c == 0 &amp;&amp; s &gt;= 3        -&gt; HIGH
 *   c &gt;= 1 &amp;&amp; s &gt; 3*c        -&gt; HIGH        (strong dominance: &gt;3:1 supporting-to-contradictory)
 *   c &gt;= 1 &amp;&amp; s - c &gt;= 3     -&gt; MODERATE    (real net corroboration, not yet 3:1-dominant)
 *   c &gt;= 1, otherwise       -&gt; LOW
 * </pre>
 *
 * <p><b>Why integer arithmetic, not a weighted or ratio formula.</b> Evidence volume here is small
 * and discrete -- {@code HypothesisDrivenProbeDiagnosticSelector.MAX_HYPOTHESIS_PROBES_PER_PACKET}
 * bounds it to at most one new observation per completed attempt -- unlike
 * {@code EvidenceConfidenceCalculatorV2}'s continuous, dozens-of-items regime, where a weighted
 * blend of ratios is defensible. Inventing decimal weights over counts of 0/1/2/3 here would be
 * false precision: a {@code 0.6234} score no one could defend against a {@code 0.61} alternative.
 * Every threshold below is an integer comparison; {@code s > 3*c} is cross-multiplication, exactly
 * equivalent to the rational test {@code s / (s + c) > 3/4} without ever computing a fraction.
 *
 * <p><b>Why the constant is always 3.</b> Three or more uncontested distinct supporting
 * observations is what "strong corroboration" means in this policy ({@code c == 0 &amp;&amp; s &gt;= 3});
 * the mixed-evidence dominance test ({@code s &gt; 3*c}) and margin test ({@code s - c &gt;= 3}) both
 * reuse that same constant rather than introducing new, independently-tuned numbers. For any
 * {@code c &gt;= 1}, {@code s &gt; 3*c} algebraically implies {@code s - c &gt; 2c &gt;= 2}, i.e.
 * {@code s - c &gt;= 3} -- so the dominance test, once satisfied, always already satisfies the margin
 * test; they are not two independent hurdles, and the rule above checks dominance first for that
 * reason, not because order matters to the result.
 *
 * <p><b>Contradiction has a real, bounded cost -- neither zero nor infinite.</b> {@code (4
 * supporting, 1 contradictory)} reaches {@code HIGH} ({@code 4 &gt; 3}): a hypothesis is not
 * permanently barred from {@code HIGH} by one historical contradiction, provided later evidence
 * establishes overwhelming (&gt;3:1) proportional dominance -- not merely a large absolute margin.
 * {@code (100 supporting, 97 contradictory)} stays at {@code MODERATE} despite an identical net
 * margin of 3 to {@code (4, 1)}: proportionally it is nearly balanced directional evidence under
 * this deterministic evidence-count model (100 out of 197, just over half), which a margin-only
 * rule would have wrongly promoted to {@code HIGH}. Neither case invokes, assumes, or requires any
 * statistical sampling model, null hypothesis, confidence interval, or significance test -- H5
 * counts distinct evidence observations and compares them by fixed integer thresholds, nothing more.
 *
 * <p><b>{@code INCONCLUSIVE} never participates.</b> It contributes to neither {@code s} nor
 * {@code c}, and cannot promote a hypothesis out of {@code INSUFFICIENT_EVIDENCE} on its own --
 * consistent with {@link HypothesisEvidenceOutcome#INCONCLUSIVE}'s own javadoc: a non-scoreable
 * response carries no directional signal either way.
 *
 * <p><b>Never fed by, and never feeds, mastery.</b> This calculator takes no {@code MasteryStatus},
 * no {@code evidenceConfidence}, no {@code MasterySnapshot} field of any kind as input, and its
 * result is never read by {@code WeightedMasteryCalculator}, {@code EvidenceConfidenceCalculatorV2},
 * or {@code MasteryStatusPolicyV2} -- see M2-ADR-023 §2.
 */
@Component
public class DiagnosticConfidenceCalculatorV1 {

  public static final String POLICY_VERSION = "DIAGNOSTIC_CONFIDENCE_V1";

  /** The one constant this policy is built from -- see the class javadoc's "why always 3." */
  private static final int STRONG_CORROBORATION_THRESHOLD = 3;

  public DiagnosticConfidenceResult compute(DiagnosticConfidenceInputs inputs) {
    int s = inputs.supportingCount();
    int c = inputs.contradictoryCount();

    DiagnosticConfidenceBand band;
    if (s == 0 && c == 0) {
      band = DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE;
    } else if (c == 0) {
      band = bandForUncontestedSupport(s);
    } else if (s > STRONG_CORROBORATION_THRESHOLD * c) {
      band = DiagnosticConfidenceBand.HIGH;
    } else if (s - c >= STRONG_CORROBORATION_THRESHOLD) {
      band = DiagnosticConfidenceBand.MODERATE;
    } else {
      band = DiagnosticConfidenceBand.LOW;
    }

    return new DiagnosticConfidenceResult(
        band, s, c, inputs.inconclusiveCount(), POLICY_VERSION);
  }

  private DiagnosticConfidenceBand bandForUncontestedSupport(int supportingCount) {
    if (supportingCount == 1) {
      return DiagnosticConfidenceBand.LOW;
    }
    if (supportingCount == 2) {
      return DiagnosticConfidenceBand.MODERATE;
    }
    return DiagnosticConfidenceBand.HIGH;
  }
}
