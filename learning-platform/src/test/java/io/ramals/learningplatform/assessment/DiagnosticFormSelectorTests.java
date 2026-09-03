package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The selection rules, exercised against synthetic pools. Every case seeds its own
 * {@link Random} so a form is reproducible: the algorithm reads nothing but its arguments.
 */
class DiagnosticFormSelectorTests {

  private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

  @Test
  void everySkillInThePoolIsRepresented() {
    List<EligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 6; skill++) {
      for (int item = 0; item < 4; item++) {
        pool.add(unseen("SKILL_" + skill, "FOUNDATIONAL"));
      }
    }

    DiagnosticForm form = selector(5).select(pool, new Random(7));

    assertThat(skillsOf(form, pool)).hasSize(6);
    assertThat(form.skillsCovered()).isEqualTo(6);
  }

  @Test
  void everyDifficultyBandInThePoolIsRepresented() {
    // One skill deep in FOUNDATIONAL items: without the difficulty pass, a three-item form drawn
    // from this pool can plausibly contain nothing but easy questions.
    List<EligibleItem> pool = new ArrayList<>();
    for (int item = 0; item < 10; item++) {
      pool.add(unseen("KAFKA_BROKER", "FOUNDATIONAL"));
    }
    pool.add(unseen("KAFKA_BROKER", "INTERMEDIATE"));
    pool.add(unseen("KAFKA_BROKER", "ADVANCED"));

    DiagnosticForm form = selector(3).select(pool, new Random(11));

    assertThat(difficultiesOf(form, pool))
        .containsExactlyInAnyOrder("FOUNDATIONAL", "INTERMEDIATE", "ADVANCED");
    assertThat(form.difficultiesCovered()).isEqualTo(3);
  }

  @Test
  void coverageOutranksTheConfiguredSize() {
    List<EligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 9; skill++) {
      pool.add(unseen("SKILL_" + skill, "FOUNDATIONAL"));
    }

    // Three items were asked for; nine skills need covering. The form grows rather than dropping
    // six skills, because a skipped skill produces no evidence and nothing downstream can tell
    // that absence apart from a learner who simply has not demonstrated it.
    DiagnosticForm form = selector(3).select(pool, new Random(3));

    assertThat(form.items()).hasSize(9);
    assertThat(skillsOf(form, pool)).hasSize(9);
  }

  @Test
  void aFormNeverExceedsThePool() {
    List<EligibleItem> pool =
        List.of(unseen("KAFKA_BROKER", "FOUNDATIONAL"), unseen("KAFKA_TOPIC", "FOUNDATIONAL"));

    DiagnosticForm form = selector(10).select(pool, new Random(1));

    assertThat(form.items()).hasSize(2);
    assertThat(form.poolSize()).isEqualTo(2);
  }

  @Test
  void recentlySeenItemsAreAvoidedWhenTheSkillOffersAnAlternative() {
    EligibleItem seenYesterday = seen("KAFKA_BROKER", "FOUNDATIONAL", NOW.minus(1, ChronoUnit.DAYS));
    EligibleItem seenLastMonth = seen("KAFKA_BROKER", "FOUNDATIONAL", NOW.minus(30, ChronoUnit.DAYS));
    EligibleItem neverSeen = unseen("KAFKA_BROKER", "FOUNDATIONAL");
    List<EligibleItem> pool = List.of(seenYesterday, seenLastMonth, neverSeen);

    DiagnosticForm form = selector(1).select(pool, new Random(5));

    assertThat(selectedIds(form)).containsExactly(neverSeen.itemVersionId());
    assertThat(form.recentlyPresentedReused()).isZero();
  }

  @Test
  void theLeastRecentlySeenItemWinsWhenEveryCandidateHasBeenSeen() {
    EligibleItem yesterday = seen("KAFKA_BROKER", "FOUNDATIONAL", NOW.minus(1, ChronoUnit.DAYS));
    EligibleItem lastMonth = seen("KAFKA_BROKER", "FOUNDATIONAL", NOW.minus(30, ChronoUnit.DAYS));

    DiagnosticForm form = selector(1).select(List.of(yesterday, lastMonth), new Random(5));

    assertThat(selectedIds(form)).containsExactly(lastMonth.itemVersionId());
  }

  @Test
  void aRecentlySeenItemIsStillTakenWhenItIsTheOnlyCoverForItsSkill() {
    EligibleItem onlyBrokerItem =
        seen("KAFKA_BROKER", "FOUNDATIONAL", NOW.minus(1, ChronoUnit.DAYS));
    List<EligibleItem> pool = new ArrayList<>();
    pool.add(onlyBrokerItem);
    pool.add(unseen("KAFKA_TOPIC", "FOUNDATIONAL"));

    DiagnosticForm form = selector(2).select(pool, new Random(13));

    // Recency is a preference, not a filter: dropping this item would leave KAFKA_BROKER
    // unmeasured, which the coverage rule ranks as the worse outcome. The reuse is reported.
    assertThat(selectedIds(form)).contains(onlyBrokerItem.itemVersionId());
    assertThat(form.recentlyPresentedReused()).isEqualTo(1);
  }

  @Test
  void fillSpreadsAcrossSkillsRatherThanDrainingTheDeepestPool() {
    List<EligibleItem> pool = new ArrayList<>();
    for (int item = 0; item < 20; item++) {
      pool.add(unseen("KAFKA_DEEP", "FOUNDATIONAL"));
    }
    pool.add(unseen("KAFKA_THIN", "FOUNDATIONAL"));
    pool.add(unseen("KAFKA_THIN", "FOUNDATIONAL"));
    pool.add(unseen("KAFKA_THIN", "FOUNDATIONAL"));

    DiagnosticForm form = selector(6).select(pool, new Random(17));

    Map<String, Long> perSkill = skillCounts(form, pool);
    assertThat(form.items()).hasSize(6);
    assertThat(perSkill).containsEntry("KAFKA_DEEP", 3L).containsEntry("KAFKA_THIN", 3L);
  }

  @Test
  void everySelectedItemIsDistinctAndPositionsAreContiguous() {
    List<EligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 4; skill++) {
      for (int item = 0; item < 5; item++) {
        pool.add(unseen("SKILL_" + skill, item % 2 == 0 ? "FOUNDATIONAL" : "ADVANCED"));
      }
    }

    DiagnosticForm form = selector(8).select(pool, new Random(23));

    assertThat(selectedIds(form)).doesNotHaveDuplicates().hasSize(8);
    assertThat(form.items()).extracting(SelectedItem::presentationOrder)
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
  }

  @Test
  void presentationOrderDoesNotFollowTheSelectionPasses() {
    // Every item covers its own skill, so all of them are taken in the first pass, in preference
    // order -- least recently seen first. If positions were assigned in that order, the item the
    // learner saw most recently would always come last.
    List<EligibleItem> pool = IntStream.range(0, 12)
        .mapToObj(index -> seen("SKILL_" + index, "FOUNDATIONAL",
            NOW.minus(index + 1, ChronoUnit.DAYS)))
        .toList();

    boolean sawAnUnsortedForm = false;
    for (int seed = 0; seed < 20 && !sawAnUnsortedForm; seed++) {
      DiagnosticForm form = selector(12).select(pool, new Random(seed));
      List<Instant> asPresented = form.items().stream()
          .map(item -> byId(pool).get(item.itemVersionId()).lastPresentedAt())
          .toList();
      sawAnUnsortedForm = !asPresented.equals(asPresented.stream().sorted().toList());
    }
    assertThat(sawAnUnsortedForm).isTrue();
  }

  @Test
  void twoDrawsFromTheSamePoolDifferButBothStayCovered() {
    List<EligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 3; skill++) {
      for (int item = 0; item < 8; item++) {
        pool.add(unseen("SKILL_" + skill, "FOUNDATIONAL"));
      }
    }

    Set<List<UUID>> distinctForms = new HashSet<>();
    for (int seed = 0; seed < 20; seed++) {
      DiagnosticForm form = selector(6).select(pool, new Random(seed));
      assertThat(skillsOf(form, pool)).hasSize(3);
      distinctForms.add(selectedIds(form).stream().sorted().toList());
    }
    assertThat(distinctForms).hasSizeGreaterThan(1);
  }

  @Test
  void theSameSeedReproducesTheSameForm() {
    List<EligibleItem> pool = new ArrayList<>();
    for (int skill = 1; skill <= 4; skill++) {
      for (int item = 0; item < 6; item++) {
        pool.add(unseen("SKILL_" + skill, "INTERMEDIATE"));
      }
    }

    assertThat(selector(7).select(pool, new Random(99)).items())
        .isEqualTo(selector(7).select(pool, new Random(99)).items());
  }

  @Test
  void everySelectionCarriesTheReasonItWasTaken() {
    // The only ADVANCED item is also the one the learner saw most recently, so the skill pass
    // passes it over and the difficulty pass is what pulls it in -- which is the point: the two
    // rules disagree here, and the reason recorded says which one decided.
    List<EligibleItem> pool = new ArrayList<>();
    pool.add(unseen("KAFKA_BROKER", "FOUNDATIONAL"));
    pool.add(unseen("KAFKA_BROKER", "FOUNDATIONAL"));
    pool.add(unseen("KAFKA_TOPIC", "FOUNDATIONAL"));
    pool.add(seen("KAFKA_TOPIC", "ADVANCED", NOW.minus(1, ChronoUnit.DAYS)));

    DiagnosticForm form = selector(4).select(pool, new Random(31));

    Map<SelectionReason, Long> reasons = form.items().stream()
        .collect(Collectors.groupingBy(SelectedItem::reason, Collectors.counting()));
    assertThat(reasons).containsEntry(SelectionReason.SKILL_COVERAGE, 2L);
    assertThat(reasons).containsEntry(SelectionReason.DIFFICULTY_COVERAGE, 1L);
    assertThat(reasons).containsEntry(SelectionReason.FILL, 1L);
  }

  @Test
  void anEmptyPoolIsRefusedRatherThanServedAsAnEmptyDiagnostic() {
    assertThatThrownBy(() -> selector(5).select(List.of(), new Random(1)))
        .isInstanceOf(EmptyItemPoolException.class);
  }

  @Test
  void theRecencyHorizonFollowsTheConfiguredWindow() {
    assertThat(selector(5, 30).recencyHorizon(NOW)).isEqualTo(NOW.minus(30, ChronoUnit.DAYS));
    assertThat(selector(5, 0).recencyHorizon(NOW)).isEqualTo(NOW);
  }

  private static DiagnosticFormSelector selector(int targetSize) {
    return selector(targetSize, 90);
  }

  private static DiagnosticFormSelector selector(int targetSize, int recencyWindowDays) {
    DiagnosticFormProperties properties = new DiagnosticFormProperties();
    properties.setTargetSize(targetSize);
    properties.setRecencyWindowDays(recencyWindowDays);
    return new DiagnosticFormSelector(properties);
  }

  private static EligibleItem unseen(String skillCode, String difficulty) {
    return new EligibleItem(UUID.randomUUID(), skillCode, difficulty, null);
  }

  private static EligibleItem seen(String skillCode, String difficulty, Instant lastPresentedAt) {
    return new EligibleItem(UUID.randomUUID(), skillCode, difficulty, lastPresentedAt);
  }

  private static List<UUID> selectedIds(DiagnosticForm form) {
    return form.items().stream().map(SelectedItem::itemVersionId).toList();
  }

  private static Map<UUID, EligibleItem> byId(List<EligibleItem> pool) {
    return pool.stream().collect(Collectors.toMap(EligibleItem::itemVersionId, Function.identity()));
  }

  private static Set<String> skillsOf(DiagnosticForm form, List<EligibleItem> pool) {
    return form.items().stream()
        .map(item -> byId(pool).get(item.itemVersionId()).skillCode())
        .collect(Collectors.toSet());
  }

  private static Set<String> difficultiesOf(DiagnosticForm form, List<EligibleItem> pool) {
    return form.items().stream()
        .map(item -> byId(pool).get(item.itemVersionId()).difficulty())
        .collect(Collectors.toSet());
  }

  private static Map<String, Long> skillCounts(DiagnosticForm form, List<EligibleItem> pool) {
    return form.items().stream()
        .map(item -> byId(pool).get(item.itemVersionId()).skillCode())
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }
}
