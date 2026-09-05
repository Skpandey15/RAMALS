package io.ramals.learningplatform.assessment;

/**
 * H5 (M2-ADR-026): {@link DiagnosticConfidenceCalculatorV1#compute}'s full, auditable result --
 * the band and the exact counts that produced it, stamped with the policy version that computed
 * them. Deliberately no numeric score: nothing today consumes one, and inventing a decimal value
 * merely to look more precise than four bands is exactly the false-precision this policy exists to
 * avoid (M2-ADR-026).
 *
 * @param band the deterministic outcome of {@link DiagnosticConfidenceCalculatorV1#compute}
 * @param supportingCount echoes the input, for audit -- see {@link DiagnosticConfidenceInputs}
 * @param contradictoryCount echoes the input, for audit
 * @param inconclusiveCount echoes the input, for audit; never influenced {@code band}
 * @param policyVersion {@link DiagnosticConfidenceCalculatorV1#POLICY_VERSION}
 */
public record DiagnosticConfidenceResult(
    DiagnosticConfidenceBand band,
    int supportingCount,
    int contradictoryCount,
    int inconclusiveCount,
    String policyVersion) {
}
