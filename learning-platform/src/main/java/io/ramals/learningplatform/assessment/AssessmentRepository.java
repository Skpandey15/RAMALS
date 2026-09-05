package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AssessmentRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AssessmentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /** Resolves the latest published diagnostic for a domain, or empty if none is published. */
  public Optional<ResolvedDiagnostic> findPublishedDiagnostic(String domainCode) {
    return jdbcTemplate.query("""
        SELECT av.id, d.code AS domain_code, a.stable_code AS assessment_code,
               av.version_code, av.status
        FROM core.assessment_version av
        JOIN core.assessment a ON a.id = av.assessment_id
        JOIN core.learning_domain d ON d.id = a.domain_id
        WHERE d.code = ? AND a.assessment_type = 'DIAGNOSTIC' AND av.status = 'PUBLISHED'
        ORDER BY av.published_at DESC
        LIMIT 1
        """, DIAGNOSTIC_MAPPER, domainCode).stream().findFirst();
  }

  public Optional<UUID> findCurriculumVersionId(UUID assessmentVersionId) {
    return jdbcTemplate.query(
        "SELECT curriculum_version_id FROM core.assessment_version WHERE id = ?",
        (result, row) -> result.getObject("curriculum_version_id", UUID.class), assessmentVersionId)
        .stream().findFirst();
  }

  /**
   * Which form-selection policy governs this version. Empty (not merely a null string) both when
   * the version does not exist and when it declares none -- either way the caller falls back to
   * {@link DiagnosticFormSelector#SELECTION_POLICY_VERSION}, V050's documented meaning of NULL.
   */
  public Optional<String> findSelectionPolicyVersion(UUID assessmentVersionId) {
    return jdbcTemplate.query(
        "SELECT selection_policy_version FROM core.assessment_version WHERE id = ?",
        (result, row) -> Optional.ofNullable(result.getString("selection_policy_version")),
        assessmentVersionId).stream().findFirst().orElse(Optional.empty());
  }

  public Optional<ResolvedDiagnostic> findDiagnosticByVersionId(UUID assessmentVersionId) {
    return jdbcTemplate.query("""
        SELECT av.id, d.code AS domain_code, a.stable_code AS assessment_code,
               av.version_code, av.status
        FROM core.assessment_version av
        JOIN core.assessment a ON a.id = av.assessment_id
        JOIN core.learning_domain d ON d.id = a.domain_id
        WHERE av.id = ?
        """, DIAGNOSTIC_MAPPER, assessmentVersionId).stream().findFirst();
  }

  public Optional<AssessmentAttempt> findByIdempotency(
      UUID learnerId, UUID assessmentVersionId, String idempotencyKey) {
    return jdbcTemplate.query(
        ATTEMPT_SELECT + " WHERE learner_id = ? AND assessment_version_id = ? AND idempotency_key = ?",
        ATTEMPT_MAPPER, learnerId, assessmentVersionId, idempotencyKey).stream().findFirst();
  }

  public Optional<AssessmentAttempt> findActiveAttempt(UUID learnerId, UUID assessmentVersionId) {
    return jdbcTemplate.query(
        ATTEMPT_SELECT
            + " WHERE learner_id = ? AND assessment_version_id = ? AND status = 'IN_PROGRESS'",
        ATTEMPT_MAPPER, learnerId, assessmentVersionId).stream().findFirst();
  }

  public Optional<AssessmentAttempt> findAttempt(UUID attemptId) {
    return jdbcTemplate.query(ATTEMPT_SELECT + " WHERE id = ?", ATTEMPT_MAPPER, attemptId)
        .stream().findFirst();
  }

  /** Loads and row-locks an attempt so concurrent submissions serialize on its state transition. */
  public Optional<AssessmentAttempt> findAttemptForUpdate(UUID attemptId) {
    return jdbcTemplate.query(
        ATTEMPT_SELECT + " WHERE id = ? FOR UPDATE", ATTEMPT_MAPPER, attemptId)
        .stream().findFirst();
  }

  /**
   * DIAGNOSTIC_SELECTION_V5 (M2-ADR-025 §4): the single source attempt a hypothesis-driven probe may
   * be raised from -- the most recent {@code COMPLETED} attempt for the same
   * {@code assessment_version_id} as the attempt being created. Scoped to the same version
   * deliberately: it is what keeps a candidate probe item guaranteed to belong to the same pool the
   * new attempt selects from, never a cross-version mismatch. Not an arbitrary history scan --
   * exactly one attempt, or none.
   */
  public Optional<AssessmentAttempt> findMostRecentCompletedAttempt(
      UUID learnerId, UUID assessmentVersionId) {
    return jdbcTemplate.query(
        ATTEMPT_SELECT + """
             WHERE learner_id = ? AND assessment_version_id = ? AND status = 'COMPLETED'
             ORDER BY created_at DESC
             LIMIT 1
            """,
        ATTEMPT_MAPPER, learnerId, assessmentVersionId).stream().findFirst();
  }

  /**
   * DIAGNOSTIC_SELECTION_V5: every item {@code attemptId} presented that this learner answered
   * incorrectly, in {@code presentation_order} -- the existing, already-deterministic field
   * {@code DiagnosticService} walks to pick which miss to try first (M2-ADR-025 §2), rather than
   * inventing a new ordering or score. Every presented item is, by construction, one this attempt's
   * own selector already restricted to a scoreable type (V047's {@code AssessmentItemType}), so no
   * further type filter is needed here.
   */
  public List<UUID> findIncorrectItemVersionIdsInPresentationOrder(UUID attemptId) {
    return jdbcTemplate.query("""
        SELECT ai.item_version_id
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_response ar
          ON ar.attempt_id = ai.attempt_id AND ar.item_version_id = ai.item_version_id
        WHERE ai.attempt_id = ? AND ar.is_correct = FALSE
        ORDER BY ai.presentation_order
        """, (result, row) -> result.getObject("item_version_id", UUID.class), attemptId);
  }

  /** Server-only: loads items with their answer keys for correctness decisions during submit. */
  public List<AssessmentItemScoringView> findItemScoringViews(UUID assessmentVersionId) {
    return jdbcTemplate.query("""
        SELECT iv.id, s.stable_code AS skill_code, iv.item_type,
               iv.options_jsonb AS options, iv.answer_key_jsonb AS answer_key
        FROM core.assessment_item_version iv
        JOIN core.skill s ON s.id = iv.skill_id
        -- M1-ADR-006: only verified content may take part in a scored context. Filtered here as
        -- well as at presentation because this is the path that decides correctness and therefore
        -- creates evidence -- an unverified item reaching only this query would produce evidence
        -- for something no learner was ever shown.
        WHERE iv.assessment_version_id = ? AND iv.trust_state = 'VERIFIED_CONTENT'
        """ + SCOREABLE_TYPE_FILTER, scoringViewMapper(), assessmentVersionId);
  }

  public void insertResponse(
      UUID attemptId, UUID itemVersionId, String responseJson, boolean correct) {
    jdbcTemplate.update("""
        INSERT INTO core.assessment_response
          (id, attempt_id, item_version_id, response_jsonb, is_correct)
        VALUES (?, ?, ?, ?::jsonb, ?)
        """, UuidV7.generate(), attemptId, itemVersionId, responseJson, correct);
  }

  /**
   * What each skill's answered items actually measured in this attempt: the objectives they are
   * tagged against, and the mastery bands their difficulty maps to.
   *
   * <p>Driven from the persisted responses rather than from the selected form, so coverage
   * describes what the learner was actually measured on. Items are joined to their objectives with
   * a LEFT JOIN: an untagged item still contributes its difficulty band and simply credits no
   * objective, which is the conservative direction for content that predates objective tagging.
   */
  public Map<String, EvidenceCoverage> findAttemptCoverage(UUID attemptId) {
    Map<String, List<UUID>> objectives = new LinkedHashMap<>();
    Map<String, Set<MasteryDifficultyBand>> bands = new LinkedHashMap<>();
    jdbcTemplate.query("""
        SELECT s.stable_code AS skill_code, iv.difficulty, aio.objective_id
        FROM core.assessment_response r
        JOIN core.assessment_item_version iv ON iv.id = r.item_version_id
        JOIN core.skill s ON s.id = iv.skill_id
        LEFT JOIN core.assessment_item_objective aio ON aio.item_version_id = iv.id
        WHERE r.attempt_id = ?
        """, (RowCallbackHandler) result -> {
          String skillCode = result.getString("skill_code");
          // Fail-closed on an unmapped difficulty: better to refuse the submission than to write
          // evidence that silently understates the bands the learner was measured at.
          bands.computeIfAbsent(skillCode, key -> new LinkedHashSet<>())
              .add(AssessmentDifficulty.bandOf(result.getString("difficulty")));
          UUID objectiveId = result.getObject("objective_id", UUID.class);
          if (objectiveId != null) {
            objectives.computeIfAbsent(skillCode, key -> new ArrayList<>()).add(objectiveId);
          }
        }, attemptId);

    Map<String, EvidenceCoverage> coverage = new LinkedHashMap<>();
    for (String skillCode : bands.keySet()) {
      coverage.put(skillCode, new EvidenceCoverage(
          List.copyOf(new LinkedHashSet<>(objectives.getOrDefault(skillCode, List.of()))),
          bands.get(skillCode)));
    }
    return coverage;
  }

  /** Reads persisted responses for scoring. Deliberately does not read the answer key. */
  public List<ScoredResponse> findScoredResponses(UUID attemptId) {
    return jdbcTemplate.query("""
        SELECT s.stable_code AS skill_code, iv.item_type,
               jsonb_array_length(iv.options_jsonb) AS option_count,
               r.is_correct
        FROM core.assessment_response r
        JOIN core.assessment_item_version iv ON iv.id = r.item_version_id
        JOIN core.skill s ON s.id = iv.skill_id
        WHERE r.attempt_id = ?
        ORDER BY s.stable_code, r.item_version_id
        """, (result, row) -> new ScoredResponse(
            result.getString("skill_code"),
            result.getString("item_type"),
            result.getInt("option_count"),
            result.getBoolean("is_correct")), attemptId);
  }

  /**
   * Every logical question this learner has ever been presented, across every attempt and every
   * assessment version, resolved through {@code core.assessment_item_lineage}.
   *
   * <p>This is the complete-history read the no-repeat guarantee depends on: it does not stop at
   * the most recent attempt, and it resolves through logical identity rather than
   * {@code item_version_id} so that an editorial revision of a question this learner already saw
   * is still recognised as seen, however its version row changed underneath it.
   *
   * <p>A version with no lineage row -- possible only for content that predates V048 -- is silently
   * excluded from this result rather than failing the query. That is the same direction V048's own
   * migration comment argues for: nothing here fabricates a logical identity for a row that was
   * never given one, and a learner's history is undercounted rather than the query refusing to run.
   */
  public Set<UUID> findLearnerExposedLogicalItemIds(UUID learnerId) {
    return Set.copyOf(jdbcTemplate.query("""
        SELECT DISTINCT lin.logical_item_id
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_attempt a ON a.id = ai.attempt_id
        JOIN core.assessment_item_lineage lin ON lin.item_version_id = ai.item_version_id
        WHERE a.learner_id = ?
        """, (result, row) -> result.getObject("logical_item_id", UUID.class), learnerId));
  }

  /** Transitions an in-progress attempt to COMPLETED. Returns true if this call finalized it. */
  public boolean completeAttempt(UUID attemptId) {
    return jdbcTemplate.update("""
        UPDATE core.assessment_attempt SET status = 'COMPLETED'
        WHERE id = ? AND status = 'IN_PROGRESS'
        """, attemptId) == 1;
  }

  /**
   * Inserts a new in-progress attempt. Throws a
   * {@link org.springframework.dao.DuplicateKeyException} if the scoped idempotency key or the
   * one-active-attempt invariant is violated by a concurrent writer.
   */
  public AssessmentAttempt insertAttempt(
      UUID learnerId, UUID assessmentVersionId, String idempotencyKey, String selectionPolicy) {
    return insertAttempt(learnerId, assessmentVersionId, idempotencyKey, selectionPolicy, null);
  }

  /**
   * @param packetPolicy which packet-composition policy decided this attempt's item types and
   *     count -- {@link AdaptiveDiagnosticSelector#PACKET_POLICY} under V2 selection, or
   *     {@code null} for the legacy V1 path, which V047's column comment documents as "predates
   *     typed packets" rather than a packet policy of its own.
   */
  public AssessmentAttempt insertAttempt(
      UUID learnerId, UUID assessmentVersionId, String idempotencyKey, String selectionPolicy,
      String packetPolicy) {
    UUID id = UuidV7.generate();
    // Master Plan §8: the attempt row records the logical interaction that created it, so an
    // attempt can be correlated back to a support ticket in SQL rather than only through logs.
    String interactionId = CorrelationContext.currentInteractionId();
    jdbcTemplate.update("""
        INSERT INTO core.assessment_attempt
          (id, learner_id, assessment_version_id, status, idempotency_key, interaction_id,
           selection_policy, packet_policy)
        VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
        """, id, learnerId, assessmentVersionId, idempotencyKey,
        interactionId.isBlank() ? null : interactionId, selectionPolicy, packetPolicy);
    return findAttempt(id).orElseThrow(
        () -> new IllegalStateException("Attempt insert did not persist a row."));
  }

  /**
   * The pool a form may be assembled from: every verified item of the pinned version, each with
   * when this learner last saw it inside {@code recencySince}.
   *
   * <p>Recency is resolved in the same statement rather than in a second round trip because the
   * selector needs the two together for every candidate, and a pool loaded separately from its
   * history could be answered from a different snapshot.
   */
  public List<EligibleItem> findEligibleItems(
      UUID assessmentVersionId, UUID learnerId, Instant recencySince) {
    return jdbcTemplate.query("""
        SELECT iv.id, s.stable_code AS skill_code, iv.difficulty, recent.last_presented_at
        FROM core.assessment_item_version iv
        JOIN core.skill s ON s.id = iv.skill_id
        LEFT JOIN LATERAL (
          SELECT max(a.created_at) AS last_presented_at
          FROM core.assessment_attempt_item ai
          JOIN core.assessment_attempt a ON a.id = ai.attempt_id
          WHERE ai.item_version_id = iv.id
            AND a.learner_id = ?
            AND a.created_at >= ?
        ) recent ON TRUE
        -- M1-ADR-006: the same filter the presentation and scoring paths apply. An unverified item
        -- must not even be a candidate, or it would reach a learner through selection.
        WHERE iv.assessment_version_id = ? AND iv.trust_state = 'VERIFIED_CONTENT'
        """ + SCOREABLE_TYPE_FILTER + """

        ORDER BY iv.display_order, iv.item_code
        """, ELIGIBLE_ITEM_MAPPER,
        learnerId, OffsetDateTime.ofInstant(recencySince, ZoneOffset.UTC), assessmentVersionId);
  }

  /**
   * The pool {@link AdaptiveDiagnosticSelector} may draw an adaptive packet from: every verified,
   * scoreable item of the pinned version, with its logical question identity.
   *
   * <p>Carries no recency and applies no per-learner exclusion -- unlike {@link #findEligibleItems},
   * which resolves recency for one learner in the same statement. The V2 no-repeat rule is a hard
   * exclusion rather than a preference, so the caller filters this pool against
   * {@link #findLearnerExposedLogicalItemIds} itself, once, rather than this query resolving it per
   * candidate for a single learner the way V1's recency join does.
   *
   * <p>Inner-joined to {@code assessment_item_lineage} rather than left-joined: an item with no
   * logical identity cannot be reasoned about for no-repeat at all, and V048's publication guard
   * already refuses to let such an item reach a published version, so excluding it here costs
   * nothing a publishable version would ever have relied on.
   */
  public List<AdaptiveEligibleItem> findAdaptiveEligibleItems(UUID assessmentVersionId) {
    return jdbcTemplate.query("""
        SELECT iv.id, lin.logical_item_id, iv.skill_id, s.stable_code AS skill_code,
               iv.item_type, iv.difficulty
        FROM core.assessment_item_version iv
        JOIN core.skill s ON s.id = iv.skill_id
        JOIN core.assessment_item_lineage lin ON lin.item_version_id = iv.id
        -- M1-ADR-006, as elsewhere: only verified, scoreable content is ever a candidate.
        WHERE iv.assessment_version_id = ? AND iv.trust_state = 'VERIFIED_CONTENT'
        """ + SCOREABLE_TYPE_FILTER + """
        ORDER BY iv.display_order, iv.item_code
        """, ADAPTIVE_ELIGIBLE_ITEM_MAPPER, assessmentVersionId);
  }

  /** Records the assembled form. Written once, inside the attempt-creation transaction. */
  public void insertSelectedItems(UUID attemptId, List<SelectedItem> items) {
    jdbcTemplate.batchUpdate("""
        INSERT INTO core.assessment_attempt_item
          (id, attempt_id, item_version_id, presentation_order, selection_reason)
        VALUES (?, ?, ?, ?, ?)
        """, items.stream()
            .map(item -> new Object[] {UuidV7.generate(), attemptId, item.itemVersionId(),
                item.presentationOrder(), item.reason().name()})
            .toList());
  }

  /**
   * Loads the items this attempt actually presents, in its own presentation order. Never selects
   * the answer key.
   *
   * <p>Falls back to the whole version pool for attempts that predate V045 and so have no recorded
   * selection. Those attempts really were served the entire pool, and the fallback is what keeps
   * them readable and submittable rather than retroactively empty. An empty result can only mean
   * that: V005 makes the items of a published version immutable, so a selected item cannot lose
   * its trust state and disappear from underneath a form that already contains it.
   *
   * <p>Filtered to scoreable types even though selection already excludes the rest: an attempt
   * assembled before V047 introduced other types has no such exclusion to have relied on, so this
   * is the one presentation path where the filter is not purely defensive.
   */
  public List<DiagnosticItem> findPresentedItems(UUID attemptId, UUID assessmentVersionId) {
    List<DiagnosticItem> selected = jdbcTemplate.query("""
        SELECT iv.id, iv.item_code, s.stable_code AS skill_code, iv.item_type, iv.stem,
               iv.options_jsonb AS options, ai.presentation_order AS display_order
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_item_version iv ON iv.id = ai.item_version_id
        JOIN core.skill s ON s.id = iv.skill_id
        WHERE ai.attempt_id = ? AND iv.trust_state = 'VERIFIED_CONTENT'
        """ + SCOREABLE_TYPE_FILTER + """

        ORDER BY ai.presentation_order
        """, itemMapper(), attemptId);
    return selected.isEmpty() ? findItems(assessmentVersionId) : selected;
  }

  /**
   * Server-only: the scoring views of the items this attempt presented, with their answer keys.
   *
   * <p>Scoped to the attempt rather than to the version, so a response naming an item that exists
   * in the pool but was never selected for this learner finds no view and is rejected. Same
   * pre-V045 fallback, and for the same reason, as {@link #findPresentedItems}.
   */
  public List<AssessmentItemScoringView> findPresentedItemScoringViews(
      UUID attemptId, UUID assessmentVersionId) {
    List<AssessmentItemScoringView> selected = jdbcTemplate.query("""
        SELECT iv.id, s.stable_code AS skill_code, iv.item_type,
               iv.options_jsonb AS options, iv.answer_key_jsonb AS answer_key
        FROM core.assessment_attempt_item ai
        JOIN core.assessment_item_version iv ON iv.id = ai.item_version_id
        JOIN core.skill s ON s.id = iv.skill_id
        -- M1-ADR-006, as in findItemScoringViews: this is the path that decides correctness and
        -- therefore creates evidence, so unverified content is filtered here too.
        WHERE ai.attempt_id = ? AND iv.trust_state = 'VERIFIED_CONTENT'
        """ + SCOREABLE_TYPE_FILTER, scoringViewMapper(), attemptId);
    return selected.isEmpty() ? findItemScoringViews(assessmentVersionId) : selected;
  }

  /** Loads presentable items for an attempt. Never selects the answer key. */
  public List<DiagnosticItem> findItems(UUID assessmentVersionId) {
    return jdbcTemplate.query("""
        SELECT iv.id, iv.item_code, s.stable_code AS skill_code, iv.item_type, iv.stem,
               iv.options_jsonb AS options, iv.display_order
        FROM core.assessment_item_version iv
        JOIN core.skill s ON s.id = iv.skill_id
        -- M1-ADR-006: unverified or rejected content is never shown to a learner.
        WHERE iv.assessment_version_id = ? AND iv.trust_state = 'VERIFIED_CONTENT'
        """ + SCOREABLE_TYPE_FILTER + """
        ORDER BY iv.display_order, iv.item_code
        """, itemMapper(), assessmentVersionId);
  }

  private RowMapper<DiagnosticItem> itemMapper() {
    return (result, row) -> new DiagnosticItem(
        result.getObject("id", UUID.class),
        result.getString("item_code"),
        result.getString("skill_code"),
        result.getString("item_type"),
        result.getString("stem"),
        parseOptions(result.getString("options")),
        result.getInt("display_order"));
  }

  private List<DiagnosticItemOption> parseOptions(String optionsJson) {
    if (optionsJson == null || optionsJson.isBlank()) {
      return List.of();
    }
    return List.of(objectMapper.readValue(optionsJson, DiagnosticItemOption[].class));
  }

  private RowMapper<AssessmentItemScoringView> scoringViewMapper() {
    return (result, row) -> {
      List<String> optionIds = parseOptions(result.getString("options")).stream()
          .map(DiagnosticItemOption::id)
          .toList();
      AnswerKey answerKey = objectMapper.readValue(result.getString("answer_key"), AnswerKey.class);
      List<String> correct = answerKey.correct() == null ? List.of() : answerKey.correct();
      List<String> accepted = answerKey.accepted() == null ? List.of() : answerKey.accepted();
      return new AssessmentItemScoringView(
          result.getObject("id", UUID.class),
          result.getString("skill_code"),
          result.getString("item_type"),
          optionIds,
          correct,
          accepted);
    };
  }

  /**
   * The item types {@link io.ramals.learningplatform.curriculum.AssessmentItemType} marks
   * deterministically scoreable. Spliced into every query that selects an item into a learner
   * form or a scoring decision, so SHORT_ANSWER and USE_CASE content -- authorable under V047 but
   * ungated for a learner until M2-ADR-022's evaluation boundary exists -- cannot reach either path
   * through any of the four queries that feed them, present or future.
   */
  private static final String SCOREABLE_TYPE_FILTER =
      "AND iv.item_type IN ('SINGLE_CHOICE', 'FILL_BLANK')\n";

  private static final String ATTEMPT_SELECT = """
      SELECT id, learner_id, assessment_version_id, status, idempotency_key, created_at, updated_at
      FROM core.assessment_attempt
      """;

  private static final RowMapper<EligibleItem> ELIGIBLE_ITEM_MAPPER = (result, row) ->
      new EligibleItem(
          result.getObject("id", UUID.class),
          result.getString("skill_code"),
          result.getString("difficulty"),
          instant(result, "last_presented_at"));

  private static final RowMapper<AdaptiveEligibleItem> ADAPTIVE_ELIGIBLE_ITEM_MAPPER = (result, row) ->
      new AdaptiveEligibleItem(
          result.getObject("id", UUID.class),
          result.getObject("logical_item_id", UUID.class),
          result.getObject("skill_id", UUID.class),
          result.getString("skill_code"),
          result.getString("item_type"),
          result.getString("difficulty"));

  private static final RowMapper<ResolvedDiagnostic> DIAGNOSTIC_MAPPER = (result, row) ->
      new ResolvedDiagnostic(
          result.getObject("id", UUID.class),
          result.getString("domain_code"),
          result.getString("assessment_code"),
          result.getString("version_code"),
          result.getString("status"));

  private static final RowMapper<AssessmentAttempt> ATTEMPT_MAPPER = (result, row) ->
      new AssessmentAttempt(
          result.getObject("id", UUID.class),
          result.getObject("learner_id", UUID.class),
          result.getObject("assessment_version_id", UUID.class),
          result.getString("status"),
          result.getString("idempotency_key"),
          instant(result, "created_at"),
          instant(result, "updated_at"));

  private static Instant instant(ResultSet result, String column) throws SQLException {
    OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
