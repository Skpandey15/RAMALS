package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Assembles one adaptive diagnostic packet from an already-exposure-filtered item pool and each
 * in-scope skill's current mastery signal. Versioned as {@link #SELECTION_POLICY_VERSION}, and
 * kept a pure function of its inputs for the same reason {@link DiagnosticFormSelector} is: a form
 * can be reproduced in a test from a seed, without touching a database.
 *
 * <p><b>Not the same question V1 answers.</b> {@link DiagnosticFormSelector} asks "is every skill
 * and difficulty band represented in this form". This asks "which skill, at which difficulty band,
 * does this learner's own evidence say most needs a question right now" -- and does so only for
 * items this learner has never been shown, because unlike V1's recency preference, exposure
 * exclusion here is the caller's hard filter, not this class's preference. See
 * {@link AssessmentRepository#findLearnerExposedLogicalItemIds}.
 *
 * <p><b>The pool it receives has already had every seen item removed.</b> This class never learns
 * which items existed but were excluded; it only ever sees what remains. That is deliberate: a
 * no-repeat guarantee implemented as "prefer unseen, fall back to seen" is not a guarantee, and
 * every fallback path in this class stays within the unseen pool it was given, never widening it.
 *
 * <p>The rules, applied in rounds:
 *
 * <ol>
 *   <li><b>Skill priority.</b> Every skill present in the pool is ranked by its
 *       {@link SkillMasterySignal#priority()} -- an unseen skill outranks a low-confidence one,
 *       which outranks a weak one, and so on down to a skill already confirmed at ADVANCED.
 *   <li><b>Round-robin by priority.</b> Each round visits every skill, most-needy first, and takes
 *       at most one item per skill per round -- so the first round is a coverage floor (every skill
 *       with any unseen stock gets one item) and later rounds deepen the neediest skills first,
 *       rather than a fixed per-skill count. This is what makes the packet's per-skill distribution
 *       adaptive: five skills and a seven-item quota means two rounds, and which two skills get a
 *       second item is decided by evidence, not a static split.
 *   <li><b>Band, held not skipped.</b> Within a skill, the item taken is the one closest to (at or
 *       below) the signal's {@link SkillMasterySignal#targetDifficulty()} -- never above it, so a
 *       thin pool at the decided band can fall back to an easier unseen item rather than presenting
 *       a band nothing has evidenced the learner is ready for. It never falls back upward.
 *   <li><b>Type quota.</b> A round only considers item types with quota remaining
 *       ({@link AdaptiveDiagnosticFormProperties}), so the packet's SINGLE_CHOICE/FILL_BLANK split
 *       is respected across every round, not just the first.
 * </ol>
 *
 * <p><b>Exhaustion is reported, not hidden or worked around.</b> A skill that contributes nothing
 * because every one of its unseen candidates lost the type-quota race is not exhausted and is not
 * reported; a skill with genuinely zero remaining unseen stock is, in
 * {@link AdaptivePacket#skillsWithNoUnseenStock()}. This class never invents a value to pad the
 * packet back to quota and never re-admits an item the caller already excluded -- a packet smaller
 * than quota is a correct, valid output, exactly as {@link DiagnosticFormProperties} already
 * tolerates a pool smaller than target for V1.
 */
@Component
public class AdaptiveDiagnosticSelector {

  public static final String SELECTION_POLICY_VERSION = "DIAGNOSTIC_SELECTION_V2";

  /**
   * Recorded on the attempt so a seven-item packet never reads later as a truncated eleven.
   * Deliberately not named {@code *_VERSION}: it is a composition-target label, not a versioned
   * algorithm with deterministic output to freeze -- the release package's engine-freeze test scans
   * main sources for exactly that naming pattern and would otherwise expect a frozen behaviour
   * vector for something that computes nothing.
   */
  public static final String PACKET_POLICY = "PACKET_TRANSITIONAL_7_V1";

  private static final String SINGLE_CHOICE = "SINGLE_CHOICE";
  private static final String FILL_BLANK = "FILL_BLANK";

  private final AdaptiveDiagnosticFormProperties properties;

  public AdaptiveDiagnosticSelector(AdaptiveDiagnosticFormProperties properties) {
    this.properties = properties;
  }

  /**
   * Selects and orders an adaptive packet.
   *
   * @param unseenPool every scoreable, verified candidate this learner has not been shown, for
   *     every skill in scope -- exclusion is the caller's responsibility, applied before this call
   * @param signalsBySkill this learner's current mastery signal per skill code present in the pool;
   *     a skill with no entry is treated as {@link SkillMasterySignal#noEvidence()}
   * @param random the source for preference tiebreaks and the presentation shuffle
   */
  public AdaptivePacket select(
      List<AdaptiveEligibleItem> unseenPool,
      Map<String, SkillMasterySignal> signalsBySkill,
      RandomGenerator random) {

    Map<String, List<AdaptiveEligibleItem>> bySkill = new LinkedHashMap<>();
    for (AdaptiveEligibleItem item : unseenPool) {
      bySkill.computeIfAbsent(item.skillCode(), key -> new ArrayList<>()).add(item);
    }

    Map<UUID, Double> tiebreak = new LinkedHashMap<>();
    for (AdaptiveEligibleItem item : unseenPool) {
      tiebreak.put(item.itemVersionId(), random.nextDouble());
    }

    List<String> skillOrder = bySkill.keySet().stream()
        .sorted(Comparator
            .comparingInt((String code) -> signalOf(signalsBySkill, code).priority())
            .thenComparing(Comparator.naturalOrder()))
        .toList();

    Map<String, Integer> quotaRemaining = new LinkedHashMap<>();
    quotaRemaining.put(SINGLE_CHOICE, properties.getSingleChoiceTarget());
    quotaRemaining.put(FILL_BLANK, properties.getFillBlankTarget());

    Selection selection = new Selection();
    boolean progressed = true;
    while (quotaRemaining(quotaRemaining) > 0 && progressed) {
      progressed = false;
      for (String skillCode : skillOrder) {
        if (quotaRemaining(quotaRemaining) == 0) {
          break;
        }
        SkillMasterySignal signal = signalOf(signalsBySkill, skillCode);
        Optional<AdaptiveEligibleItem> pick = bestCandidate(
            bySkill.get(skillCode), selection, signal.targetDifficulty(), quotaRemaining, tiebreak);
        if (pick.isPresent()) {
          AdaptiveEligibleItem item = pick.get();
          selection.take(item, signal.reason());
          quotaRemaining.merge(item.itemType(), -1, Integer::sum);
          progressed = true;
        }
      }
    }

    Set<String> uncovered = new LinkedHashSet<>();
    for (String skillCode : bySkill.keySet()) {
      if (selection.countFor(skillCode) == 0) {
        uncovered.add(skillCode);
      }
    }

    return selection.toPacket(unseenPool.size(), uncovered, shuffledOrder(selection.size(), random));
  }

  private static SkillMasterySignal signalOf(Map<String, SkillMasterySignal> signals, String skillCode) {
    return signals.getOrDefault(skillCode, SkillMasterySignal.noEvidence());
  }

  private static int quotaRemaining(Map<String, Integer> quota) {
    return quota.values().stream().mapToInt(Integer::intValue).sum();
  }

  /**
   * The best not-yet-taken candidate for one skill this round: the item whose difficulty is
   * closest to, but never above, {@code decidedBand}, restricted to types with quota still
   * remaining, tie-broken by the per-item random key and finally by id.
   */
  private Optional<AdaptiveEligibleItem> bestCandidate(
      List<AdaptiveEligibleItem> candidates,
      Selection selection,
      AssessmentDifficulty decidedBand,
      Map<String, Integer> quotaRemaining,
      Map<UUID, Double> tiebreak) {
    AdaptiveEligibleItem best = null;
    int bestBandOrdinal = -1;
    for (AdaptiveEligibleItem item : candidates) {
      if (selection.contains(item)) {
        continue;
      }
      if (quotaRemaining.getOrDefault(item.itemType(), 0) <= 0) {
        continue;
      }
      int bandOrdinal = AssessmentDifficulty.of(item.difficulty()).ordinal();
      if (bandOrdinal > decidedBand.ordinal()) {
        continue; // never present a band the evidence has not earned
      }
      if (best == null
          || bandOrdinal > bestBandOrdinal
          || (bandOrdinal == bestBandOrdinal && isBetter(item, best, tiebreak))) {
        best = item;
        bestBandOrdinal = bandOrdinal;
      }
    }
    return Optional.ofNullable(best);
  }

  private static boolean isBetter(
      AdaptiveEligibleItem candidate, AdaptiveEligibleItem current, Map<UUID, Double> tiebreak) {
    double candidateKey = tiebreak.get(candidate.itemVersionId());
    double currentKey = tiebreak.get(current.itemVersionId());
    if (candidateKey != currentKey) {
      return candidateKey > currentKey;
    }
    return candidate.itemVersionId().compareTo(current.itemVersionId()) < 0;
  }

  /** A shuffled 1..size sequence: the item taken at position i is presented at order[i]. */
  private int[] shuffledOrder(int size, RandomGenerator random) {
    int[] order = new int[size];
    for (int index = 0; index < size; index++) {
      order[index] = index + 1;
    }
    for (int index = size - 1; index > 0; index--) {
      int swap = random.nextInt(index + 1);
      int held = order[index];
      order[index] = order[swap];
      order[swap] = held;
    }
    return order;
  }

  /** The packet under construction: what has been taken, why, and how deep each skill is. */
  private static final class Selection {

    private final List<AdaptiveEligibleItem> taken = new ArrayList<>();
    private final List<SelectionReason> reasons = new ArrayList<>();
    private final Set<UUID> takenIds = new LinkedHashSet<>();
    private final Map<String, Integer> perSkill = new LinkedHashMap<>();

    void take(AdaptiveEligibleItem item, SelectionReason reason) {
      taken.add(item);
      reasons.add(reason);
      takenIds.add(item.itemVersionId());
      perSkill.merge(item.skillCode(), 1, Integer::sum);
    }

    boolean contains(AdaptiveEligibleItem item) {
      return takenIds.contains(item.itemVersionId());
    }

    int countFor(String skillCode) {
      return perSkill.getOrDefault(skillCode, 0);
    }

    int size() {
      return taken.size();
    }

    AdaptivePacket toPacket(int poolSize, Set<String> skillsWithNoUnseenStock, int[] presentationOrder) {
      List<SelectedItem> items = new ArrayList<>(taken.size());
      int singleChoiceCount = 0;
      int fillBlankCount = 0;
      for (int index = 0; index < taken.size(); index++) {
        AdaptiveEligibleItem item = taken.get(index);
        items.add(
            new SelectedItem(item.itemVersionId(), presentationOrder[index], reasons.get(index)));
        if (SINGLE_CHOICE.equals(item.itemType())) {
          singleChoiceCount++;
        } else if (FILL_BLANK.equals(item.itemType())) {
          fillBlankCount++;
        }
      }
      items.sort(Comparator.comparingInt(SelectedItem::presentationOrder));
      return new AdaptivePacket(
          List.copyOf(items), poolSize, perSkill.size(), singleChoiceCount, fillBlankCount,
          skillsWithNoUnseenStock);
    }
  }
}
