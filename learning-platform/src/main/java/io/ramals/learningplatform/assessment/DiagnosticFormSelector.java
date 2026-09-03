package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Assembles one diagnostic form from the eligible item pool. Versioned, and deliberately a pure
 * function of its inputs: the pool, the recency the pool rows carry, and the random source handed
 * in. It reads nothing and writes nothing, so a form can be reproduced in a test from a seed.
 *
 * <p>The rules, in the order they are applied:
 *
 * <ol>
 *   <li><b>Skill coverage.</b> Every skill represented in the pool contributes at least one item.
 *   <li><b>Difficulty coverage.</b> Every difficulty band represented in the pool contributes at
 *       least one item.
 *   <li><b>Fill.</b> Remaining slots up to the configured size go to the skills currently holding
 *       the fewest items, so a larger form spreads rather than piling onto one skill.
 * </ol>
 *
 * <p><b>Coverage is a floor, not a budget.</b> If the pool spans more skills than the configured
 * size, the form grows to cover them rather than dropping skills to fit. A diagnostic that skips a
 * skill produces no evidence for it, and the mastery engine cannot tell that absence apart from a
 * skill the learner simply has not demonstrated -- so the failure would be silent and would land in
 * the learner's mastery record. A form that came out longer than configured is at least visible:
 * {@link DiagnosticForm} carries the counts, and the caller logs them.
 *
 * <p><b>Recency is a preference, not a filter.</b> Within every one of those passes, items the
 * learner has not recently seen are taken first, then the least recently seen, then a random draw.
 * Excluding recent items outright would let a thin pool break coverage, which the ordering above
 * says is the worse outcome; so a recently seen item is reused when it is the only way to cover its
 * skill, and the reuse is counted rather than hidden.
 *
 * <p><b>Presentation order is drawn separately from selection.</b> The passes above produce items
 * in a revealing sequence -- coverage first, fill last, each ordered by how long ago the learner
 * saw it. Shown in that order it would leak the shape of the selection to anyone comparing two
 * attempts, so the assembled set is shuffled before positions are assigned.
 *
 * <p>The difficulty vocabulary here is the item vocabulary of V005 (FOUNDATIONAL, INTERMEDIATE,
 * ADVANCED), which is a different axis from {@code skill_version.required_difficulty_bands} (EASY,
 * MEDIUM, HARD) used by the mastery status policy. Bands are read from the pool rather than
 * enumerated, so this class needs no opinion about either vocabulary.
 */
@Component
public class DiagnosticFormSelector {

  public static final String SELECTION_POLICY_VERSION = "DIAGNOSTIC_SELECTION_V1";

  private final DiagnosticFormProperties properties;

  public DiagnosticFormSelector(DiagnosticFormProperties properties) {
    this.properties = properties;
  }

  /**
   * The instant before which a presentation no longer counts as recent.
   *
   * <p>Lives here rather than at the call site because the window is part of the selection policy:
   * a caller that computed its own horizon could quietly widen or narrow the preference this class
   * documents, and the policy version would still claim the behaviour was unchanged.
   */
  public Instant recencyHorizon(Instant now) {
    return now.minus(properties.getRecencyWindowDays(), ChronoUnit.DAYS);
  }

  /**
   * Selects and orders a form.
   *
   * @param pool every eligible item, each carrying when this learner last saw it
   * @param random the source for preference tiebreaks and for the presentation shuffle
   * @throws EmptyItemPoolException if the pool is empty, which no published version can produce
   */
  public DiagnosticForm select(List<EligibleItem> pool, RandomGenerator random) {
    if (pool.isEmpty()) {
      throw new EmptyItemPoolException(
          "No verified items are available to assemble a diagnostic form.");
    }

    // One random key per item, drawn once. Drawing inside the comparator instead would make the
    // ordering non-transitive, which leaves the result of the sort undefined.
    List<Candidate> candidates = new ArrayList<>(pool.size());
    for (EligibleItem item : pool) {
      candidates.add(new Candidate(item, random.nextDouble()));
    }
    candidates.sort(BY_PREFERENCE);

    Selection selection = new Selection();
    coverSkills(candidates, selection);
    coverDifficulties(candidates, selection);
    fill(candidates, selection);

    return selection.toForm(pool.size(), shuffledOrder(selection.size(), random));
  }

  /** Every skill in the pool contributes its most-preferred item. */
  private void coverSkills(List<Candidate> candidates, Selection selection) {
    Set<String> covered = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      if (covered.add(candidate.item().skillCode())) {
        selection.take(candidate, SelectionReason.SKILL_COVERAGE);
      }
    }
  }

  /** Every difficulty band the skill pass left unrepresented contributes its best item. */
  private void coverDifficulties(List<Candidate> candidates, Selection selection) {
    Set<String> covered = new LinkedHashSet<>();
    for (Candidate taken : selection.taken()) {
      covered.add(taken.item().difficulty());
    }
    for (Candidate candidate : candidates) {
      if (!selection.contains(candidate) && covered.add(candidate.item().difficulty())) {
        selection.take(candidate, SelectionReason.DIFFICULTY_COVERAGE);
      }
    }
  }

  /**
   * Tops the form up to the configured size, always from the skill currently holding the fewest
   * items. A plain "most preferred remaining" fill would hand every spare slot to whichever skill
   * happens to own the deepest pool, and the learner would answer six questions on one skill and
   * one on each of the others.
   */
  private void fill(List<Candidate> candidates, Selection selection) {
    int targetSize = Math.min(properties.getTargetSize(), candidates.size());
    while (selection.size() < targetSize) {
      Candidate next = null;
      for (Candidate candidate : candidates) {
        if (selection.contains(candidate)) {
          continue;
        }
        // candidates is already in preference order, so the first candidate seen at a given skill
        // depth is also the most preferred one at that depth.
        if (next == null
            || selection.countFor(candidate.item().skillCode())
                < selection.countFor(next.item().skillCode())) {
          next = candidate;
        }
      }
      if (next == null) {
        return;
      }
      selection.take(next, SelectionReason.FILL);
    }
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

  /**
   * Least recently seen first, unseen ahead of all of them, ties broken by the per-item random key
   * and finally by id so the ordering is total and never depends on the pool's arrival order.
   */
  private static final Comparator<Candidate> BY_PREFERENCE =
      Comparator.comparing((Candidate candidate) -> candidate.item().recentlyPresented())
          .thenComparing(candidate -> candidate.item().lastPresentedAt(),
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparingDouble(Candidate::tiebreak)
          .thenComparing(candidate -> candidate.item().itemVersionId());

  /** An eligible item with the random key that breaks its preference ties. */
  private record Candidate(EligibleItem item, double tiebreak) {
  }

  /** The form under construction: what has been taken, why, and how deep each skill is. */
  private static final class Selection {

    private final List<Candidate> taken = new ArrayList<>();
    private final List<SelectionReason> reasons = new ArrayList<>();
    private final Set<UUID> takenIds = new LinkedHashSet<>();
    private final Map<String, Integer> perSkill = new LinkedHashMap<>();

    void take(Candidate candidate, SelectionReason reason) {
      taken.add(candidate);
      reasons.add(reason);
      takenIds.add(candidate.item().itemVersionId());
      perSkill.merge(candidate.item().skillCode(), 1, Integer::sum);
    }

    boolean contains(Candidate candidate) {
      return takenIds.contains(candidate.item().itemVersionId());
    }

    int countFor(String skillCode) {
      return perSkill.getOrDefault(skillCode, 0);
    }

    List<Candidate> taken() {
      return taken;
    }

    int size() {
      return taken.size();
    }

    DiagnosticForm toForm(int poolSize, int[] presentationOrder) {
      List<SelectedItem> items = new ArrayList<>(taken.size());
      Set<String> difficulties = new LinkedHashSet<>();
      int reused = 0;
      for (int index = 0; index < taken.size(); index++) {
        EligibleItem item = taken.get(index).item();
        items.add(
            new SelectedItem(item.itemVersionId(), presentationOrder[index], reasons.get(index)));
        difficulties.add(item.difficulty());
        if (item.recentlyPresented()) {
          reused++;
        }
      }
      items.sort(Comparator.comparingInt(SelectedItem::presentationOrder));
      return new DiagnosticForm(
          List.copyOf(items), poolSize, perSkill.size(), difficulties.size(), reused);
    }
  }
}
