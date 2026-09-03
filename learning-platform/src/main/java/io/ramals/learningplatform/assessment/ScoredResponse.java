package io.ramals.learningplatform.assessment;

/**
 * A persisted response reduced to the inputs deterministic scoring needs. Carries no answer key;
 * correctness was decided once, at submit time, and is read back from storage.
 *
 * <p>{@code itemType} decides how {@link DiagnosticScorerV2} estimates the chance of a correct
 * guess for the item: a SINGLE_CHOICE guess probability is a function of {@code optionCount}, but a
 * FILL_BLANK item has no options to guess among -- {@code optionCount} is meaningless for it, and
 * treating it as if it meant something would either inflate or deflate the chance-corrected score
 * for a reason that has nothing to do with the item.
 */
public record ScoredResponse(String skillCode, String itemType, int optionCount, boolean correct) {
}
