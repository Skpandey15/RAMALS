/**
 * {@code HYPOTHESIS_DISCRIMINATION_V1} (M2-ADR-034 Amendment 2, Step 2): a deterministic,
 * non-expectation discrimination score -- the total variation distance between two hypothetical
 * {@code HYPOTHESIS_UNCERTAINTY_V1} outcomes -- per candidate probe. Inert: nothing in {@code
 * io.ramals.learningplatform.assessment} calls this package, and it does not consume, and is not
 * consumed by, {@code DIAGNOSTIC_SELECTION_V1}-{@code V5} or the misconception relationship graph.
 */
package io.ramals.learningplatform.assessment.hypothesisdiscrimination;
