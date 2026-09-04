package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentItemType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * DiagnosticScorerV2 -- {@link DiagnosticScorer} extended to score FILL_BLANK as well as
 * SINGLE_CHOICE, and versioned separately rather than edited in place.
 *
 * <p><b>Why a new class instead of a new branch in the old one.</b> V1 is frozen: its version
 * identifier is stamped onto every diagnostic evidence row already written, and
 * {@code EngineVersionFreezeTests} exists specifically to make an in-place edit to a frozen engine
 * fail the build. Adding FILL_BLANK support by editing {@link DiagnosticScorer} would be exactly
 * that edit -- observable behaviour on the SINGLE_CHOICE path would be unchanged today, but the
 * class`s meaning would no longer be pinned, and nothing would stop a later change from drifting it.
 * V1 keeps its frozen hash and is no longer called by new code, the same way
 * {@code EvidenceConfidenceCalculator} was superseded rather than amended when objective coverage
 * became measurable.
 *
 * <p><b>SINGLE_CHOICE scoring is unchanged.</b> {@link #isCorrect} for that type is exact-set
 * matching, byte-for-byte the same rule V1 applies. The mastery score a learner gets for an MCQ
 * they answer today is not different under V2; what is different is that a FILL_BLANK item can be
 * scored at all.
 *
 * <p><b>FILL_BLANK correctness is exact match after fixed, documented normalization</b> -- trim,
 * lower-case, collapse runs of internal whitespace to one space -- against every string in the
 * item's {@code accepted} list. No edit-distance, no stemming, no partial credit: the plan this
 * class implements is explicit that fuzzy matching must not turn a wrong answer into a correct one,
 * and a near-miss like a single transposed letter is refused precisely because it was not typed.
 *
 * <p><b>Guess-probability policy for FILL_BLANK.</b> V1's chance correction is
 * {@code 1 / optionCount}, which is meaningless for a free-text item -- there is no option set to
 * guess among. This is not silently reused with whatever {@code optionCount} the row happens to
 * carry (V047 requires it to be zero for a non-SINGLE_CHOICE item, which would make the correction
 * blow up rather than merely be wrong). The policy adopted here is explicit: a FILL_BLANK item's
 * guess probability is treated as {@code 0}, because production quality open-ended fill-in
 * questions are written so that a correct answer typed by chance is not a realistic event. This is
 * a versioned modelling choice, stated once, and any future change to it requires a new scoring
 * version -- it does not become V2's problem to relitigate per call site.
 */
@Component
public class DiagnosticScorerV2 {

  public static final String SCORING_VERSION = "DIAGNOSTIC_SCORING_V2";
  private static final int SCALE = 4;

  /** Collapses any run of whitespace to a single space, after trimming and lower-casing. */
  public boolean isCorrect(AssessmentItemScoringView view, List<String> selectedOptions) {
    AssessmentItemType type = AssessmentItemType.of(view.itemType());
    return switch (type) {
      case SINGLE_CHOICE ->
          Set.copyOf(selectedOptions).equals(Set.copyOf(view.correctOptions()));
      case FILL_BLANK -> {
        String submitted = normalize(selectedOptions.getFirst());
        yield view.acceptedAnswers().stream().map(DiagnosticScorerV2::normalize)
            .anyMatch(accepted -> accepted.equals(submitted));
      }
      case SHORT_ANSWER, USE_CASE -> throw new IllegalStateException(
          "item " + view.itemVersionId() + " is type " + type
              + ", which has no deterministic scorer and must never reach diagnostic scoring");
    };
  }

  /** Aggregates persisted responses into deterministic per-skill scores, ordered by skill code. */
  public List<SkillScore> aggregate(List<ScoredResponse> responses) {
    Map<String, List<ScoredResponse>> bySkill = new TreeMap<>();
    for (ScoredResponse response : responses) {
      bySkill.computeIfAbsent(response.skillCode(), key -> new ArrayList<>()).add(response);
    }

    List<SkillScore> scores = new ArrayList<>();
    for (Map.Entry<String, List<ScoredResponse>> entry : bySkill.entrySet()) {
      List<ScoredResponse> items = entry.getValue();
      int answered = items.size();
      int correct = (int) items.stream().filter(ScoredResponse::correct).count();
      BigDecimal observed = scaled(BigDecimal.valueOf(correct), answered);
      BigDecimal guessProbability = averageGuessProbability(items);
      BigDecimal normalized = normalize(observed, guessProbability);
      scores.add(new SkillScore(entry.getKey(), answered, correct, observed, normalized));
    }
    return scores;
  }

  private BigDecimal averageGuessProbability(List<ScoredResponse> items) {
    BigDecimal sum = BigDecimal.ZERO;
    for (ScoredResponse item : items) {
      sum = sum.add(guessProbability(item));
    }
    return scaled(sum, items.size());
  }

  private BigDecimal guessProbability(ScoredResponse item) {
    AssessmentItemType type = AssessmentItemType.of(item.itemType());
    return switch (type) {
      // Unchanged from V1: floored at 2 so a malformed option count never inflates the correction
      // past what a two-option item would apply.
      case SINGLE_CHOICE -> BigDecimal.ONE.divide(
          BigDecimal.valueOf(Math.max(item.optionCount(), 2)), 10, RoundingMode.HALF_UP);
      // The explicit FILL_BLANK policy this class documents: treated as unguessable by chance.
      case FILL_BLANK -> BigDecimal.ZERO;
      case SHORT_ANSWER, USE_CASE -> throw new IllegalStateException(
          "scored response carries type " + type + ", which has no guess-probability policy "
              + "because it has no deterministic scorer");
    };
  }

  private BigDecimal normalize(BigDecimal observed, BigDecimal guessProbability) {
    BigDecimal denominator = BigDecimal.ONE.subtract(guessProbability);
    if (denominator.signum() <= 0) {
      return observed;
    }
    BigDecimal corrected = observed.subtract(guessProbability)
        .divide(denominator, SCALE, RoundingMode.HALF_UP);
    return corrected.signum() < 0 ? BigDecimal.ZERO.setScale(SCALE) : corrected;
  }

  private BigDecimal scaled(BigDecimal numerator, int denominator) {
    if (denominator == 0) {
      return BigDecimal.ZERO.setScale(SCALE);
    }
    return numerator.divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
  }

  /**
   * trim -> casefold -> collapse internal whitespace to one space. Deterministic, and the whole
   * of it: no accent stripping, no punctuation removal, no synonym table. A fill-blank item that
   * needs one of those to be answerable fairly is a content defect to fix in the item, not a
   * reason to widen what the scorer will accept.
   */
  private static String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
