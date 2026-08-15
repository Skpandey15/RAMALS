package io.ramals.learningplatform.assessment;

/**
 * A persisted response reduced to the inputs deterministic scoring needs. Carries no answer key;
 * correctness was decided once, at submit time, and is read back from storage.
 */
public record ScoredResponse(String skillCode, int optionCount, boolean correct) {
}
