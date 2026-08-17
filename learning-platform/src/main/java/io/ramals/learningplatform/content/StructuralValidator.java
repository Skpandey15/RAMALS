package io.ramals.learningplatform.content;

import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Shape only. The cheapest stage, so it runs first and spends nothing on content that is malformed. */
@Component
public class StructuralValidator implements ContentValidator {

  private static final Set<String> ITEM_TYPES = Set.of("SINGLE_CHOICE");
  private static final Set<String> DIFFICULTIES =
      Set.of("FOUNDATIONAL", "INTERMEDIATE", "ADVANCED");

  @Override
  public ValidationStage stage() {
    return ValidationStage.STRUCTURAL;
  }

  @Override
  public Optional<String> reject(CandidateContent candidate, ValidationContext context) {
    if (candidate.itemCode() == null || !candidate.itemCode().matches("^[A-Z][A-Z0-9_]*$")) {
      return Optional.of("item code must be an uppercase identifier");
    }
    if (candidate.stem() == null || candidate.stem().isBlank()) {
      return Optional.of("stem is empty");
    }
    if (!ITEM_TYPES.contains(candidate.itemType())) {
      return Optional.of("unsupported item type");
    }
    if (!DIFFICULTIES.contains(candidate.difficulty())) {
      return Optional.of("unsupported difficulty band");
    }
    if (candidate.options() == null || candidate.options().size() < 2) {
      return Optional.of("an item needs at least two options");
    }
    if (candidate.correctOptionIds() == null || candidate.correctOptionIds().isEmpty()) {
      return Optional.of("no correct option is marked");
    }
    // An item whose key names an option that does not exist is unanswerable correctly, and would
    // score every learner wrong while looking entirely well-formed in a review queue.
    if (!candidate.options().containsAll(candidate.correctOptionIds())) {
      return Optional.of("answer key names an option that is not present");
    }
    if (candidate.correctOptionIds().size() == candidate.options().size()) {
      return Optional.of("every option is marked correct");
    }
    return Optional.empty();
  }
}
