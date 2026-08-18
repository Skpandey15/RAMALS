package io.ramals.learningplatform.content;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Content quality and safety. The last automated stage, and the most expensive.
 *
 * <p>Everything here is a refusal a machine can justify. What it deliberately does not attempt is
 * the question that decides whether an item is any good -- does it measure the objective it claims
 * to -- because that is a question about curriculum intent, not about the text. That question is
 * why M1-ADR-006 requires a human.
 */
@Component
public class QualitySafetyValidator implements ContentValidator {

  private static final int MAX_STEM_CHARS = 1200;

  /** Phrasing that makes an item unanswerable or ambiguous regardless of the learner's knowledge. */
  private static final Set<String> AMBIGUOUS_MARKERS =
      Set.of("all of the above", "none of the above", "both a and b");

  @Override
  public ValidationStage stage() {
    return ValidationStage.QUALITY_SAFETY;
  }

  @Override
  public Optional<String> reject(CandidateContent candidate, ValidationContext context) {
    if (candidate.stem().length() > MAX_STEM_CHARS) {
      return Optional.of("stem is too long to read as a single question");
    }

    List<String> options = candidate.options();
    if (options.size() != Set.copyOf(options).size()) {
      // Duplicate options make the correct answer ambiguous even when the key is right, and the
      // resulting evidence is noise attributed to a skill.
      return Optional.of("options contain duplicates");
    }

    for (String option : options) {
      if (option == null || option.isBlank()) {
        return Optional.of("an option is empty");
      }
      if (AMBIGUOUS_MARKERS.contains(option.toLowerCase(Locale.ROOT).trim())) {
        return Optional.of("aggregate options make the item ambiguous");
      }
    }

    if (candidate.stem().toLowerCase(Locale.ROOT).contains("as an ai")) {
      // A generator narrating itself into the item. Harmless to a reader, corrosive to trust, and a
      // reliable signal the generation went sideways.
      return Optional.of("stem contains generator narration");
    }

    return Optional.empty();
  }
}
