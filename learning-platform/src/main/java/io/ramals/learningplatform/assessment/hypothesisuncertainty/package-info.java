/**
 * {@code HYPOTHESIS_UNCERTAINTY_V1} (M2-ADR-034 Amendment 1, Step 1): a deterministic, non-Bayesian,
 * normalized relative hypothesis-uncertainty distribution over an already-authorized bounded
 * candidate hypothesis set, from per-interaction governed probe evidence only.
 *
 * <p>{@link io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1}
 * is the pure mathematical core: it reuses {@link
 * io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1} verbatim for each
 * candidate's confidence band, weights the graded bands {@code LOW=1, MODERATE=2, HIGH=3} (the
 * complete constant set -- {@code INSUFFICIENT_EVIDENCE} carries no weight and is never a point on
 * the scale), and normalizes with a scale-4 {@code BigDecimal}, a scale-20 {@code HALF_EVEN}
 * intermediate division, and a deterministic largest-remainder (Hamilton) residual allocation
 * tie-broken by a total canonical order on hypothesis identity. {@link
 * io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContextAssembler}
 * is the separate, non-pure boundary that turns repository state into the calculator's input; the
 * calculator itself touches no database, clock, HTTP, MCP, LLM, or random source.
 *
 * <p><b>Inert (Step 1 only).</b> Nothing in {@code io.ramals.learningplatform.assessment} calls
 * either class. No {@code DIAGNOSTIC_SELECTION_V1}-{@code V5} selector reads a {@link
 * io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult}; this
 * package reads no selector. No {@code INFORMATION_GAIN_V1} and no {@code DIAGNOSTIC_SELECTION_V6}
 * exist here or anywhere -- those are M2-ADR-034 Steps 2 and 3, design-only. The
 * {@link io.ramals.learningplatform.assessment.misconceptiongraph} package (M2-ADR-033) is not
 * consulted: this package takes no graph edge as input, defines no edge-type weight, and reads no
 * H6/H7/G3 projection. Nothing here writes learner state, mastery, or a probe.
 *
 * <p>See {@code docs/adr/M2-ADR-034-information-gain-probe-selection.md}, Amendment 1, for the
 * complete frozen specification this package implements verbatim.
 */
package io.ramals.learningplatform.assessment.hypothesisuncertainty;
