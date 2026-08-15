package io.ramals.learningplatform.learning;

/** The policy's progression decision for a skill: a state and a stable reason code. */
public record ProgressionOutcome(ProgressionState state, String reasonCode) {
}
