package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.mastery.SkillMasteryConfig;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Diagnostic attempt lifecycle. Attempt creation is retry-safe: a repeated Idempotency-Key returns
 * the same logical attempt, and the one-active-attempt invariant is enforced even when two requests
 * with different keys race. Learner identity is always taken from the authenticated subject.
 */
@Service
public class DiagnosticService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticService.class);

  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

  private final AssessmentRepository repository;
  private final LearnerService learnerService;
  private final DiagnosticFormSelector selector;
  private final AdaptiveDiagnosticSelector adaptiveSelector;
  private final MasteryRepository masteryRepository;
  private final CurriculumService curriculumService;

  public DiagnosticService(
      AssessmentRepository repository,
      LearnerService learnerService,
      DiagnosticFormSelector selector,
      AdaptiveDiagnosticSelector adaptiveSelector,
      MasteryRepository masteryRepository,
      CurriculumService curriculumService) {
    this.repository = repository;
    this.learnerService = learnerService;
    this.selector = selector;
    this.adaptiveSelector = adaptiveSelector;
    this.masteryRepository = masteryRepository;
    this.curriculumService = curriculumService;
  }

  @Transactional
  public AttemptCreation createAttempt(String subject, String domainCode, String idempotencyKey) {
    String key = normalizeIdempotencyKey(idempotencyKey);
    String normalizedDomain = domainCode.toUpperCase(Locale.ROOT);
    Learner learner = learnerService.currentLearner(subject);
    ResolvedDiagnostic diagnostic = repository.findPublishedDiagnostic(normalizedDomain)
        .orElseThrow(() -> new DiagnosticNotFoundException(normalizedDomain));
    UUID versionId = diagnostic.assessmentVersionId();

    Optional<AssessmentAttempt> byKey = repository.findByIdempotency(learner.id(), versionId, key);
    if (byKey.isPresent()) {
      return new AttemptCreation(byKey.get(), diagnostic, false);
    }
    Optional<AssessmentAttempt> active = repository.findActiveAttempt(learner.id(), versionId);
    if (active.isPresent()) {
      return new AttemptCreation(active.get(), diagnostic, false);
    }
    try {
      String selectionPolicy = repository.findSelectionPolicyVersion(versionId)
          .orElse(DiagnosticFormSelector.SELECTION_POLICY_VERSION);
      // V3 composes with V2's round-robin unchanged (see PrerequisiteAwareDiagnosticSelector), so
      // the packet it produces has the same shape V2's does -- one packet-policy label for both.
      boolean isAdaptivePacket = AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION.equals(selectionPolicy)
          || PrerequisiteAwareDiagnosticSelector.SELECTION_POLICY_VERSION.equals(selectionPolicy);
      String packetPolicy = isAdaptivePacket ? AdaptiveDiagnosticSelector.PACKET_POLICY : null;
      AssessmentAttempt created =
          repository.insertAttempt(learner.id(), versionId, key, selectionPolicy, packetPolicy);
      selectForm(created, learner.id(), diagnostic, selectionPolicy);
      BusinessEventLogger.info(LOGGER, "assessment.started", "Diagnostic assessment started",
          Map.of("entityType", "ASSESSMENT_ATTEMPT", "entityId", created.id(),
              "learnerId", learner.id(), "outcome", "SUCCESS"));
      return new AttemptCreation(created, diagnostic, true);
    } catch (DuplicateKeyException concurrentCreate) {
      AssessmentAttempt resolved = repository.findByIdempotency(learner.id(), versionId, key)
          .or(() -> repository.findActiveAttempt(learner.id(), versionId))
          .orElseThrow(() -> concurrentCreate);
      return new AttemptCreation(resolved, diagnostic, false);
    }
  }

  @Transactional(readOnly = true)
  public AttemptDetail getAttempt(String subject, String domainCode, String rawAttemptId) {
    UUID attemptId = parseAttemptId(rawAttemptId);
    Learner learner = learnerService.findLearner(subject)
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    AssessmentAttempt attempt = repository.findAttempt(attemptId)
        .filter(candidate -> candidate.learnerId().equals(learner.id()))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    ResolvedDiagnostic diagnostic = repository.findDiagnosticByVersionId(attempt.assessmentVersionId())
        .filter(resolved -> resolved.domainCode().equalsIgnoreCase(domainCode))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    return new AttemptDetail(attempt, diagnostic,
        repository.findPresentedItems(attempt.id(), attempt.assessmentVersionId()));
  }

  /**
   * Assembles this attempt's form and records it, through whichever selector the version declares.
   *
   * <p>Runs inside the creating transaction, so an attempt never becomes visible without the
   * questions it is an attempt at. It runs only on the branch that actually inserted an attempt:
   * every other branch of {@link #createAttempt} returns an attempt that already exists, and
   * re-selecting for it would either duplicate the form or -- worse, if the second draw differed --
   * change the questions under a learner who is midway through answering them. That guarantee is
   * what makes an idempotent retry safe under either selector: the draw happens at most once per
   * attempt, so replaying an Idempotency-Key never re-exposes a question.
   */
  private void selectForm(
      AssessmentAttempt attempt, UUID learnerId, ResolvedDiagnostic diagnostic, String selectionPolicy) {
    if (PrerequisiteAwareDiagnosticSelector.SELECTION_POLICY_VERSION.equals(selectionPolicy)) {
      selectPrerequisiteAwareForm(attempt, learnerId, diagnostic);
    } else if (AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION.equals(selectionPolicy)) {
      selectAdaptiveForm(attempt, learnerId, diagnostic.assessmentVersionId());
    } else {
      selectLegacyForm(attempt, learnerId, diagnostic.assessmentVersionId());
    }
  }

  private void selectLegacyForm(AssessmentAttempt attempt, UUID learnerId, UUID assessmentVersionId) {
    Instant recencySince = selector.recencyHorizon(Instant.now());
    List<EligibleItem> pool =
        repository.findEligibleItems(assessmentVersionId, learnerId, recencySince);
    DiagnosticForm form = selector.select(pool, ThreadLocalRandom.current());
    repository.insertSelectedItems(attempt.id(), form.items());
    BusinessEventLogger.info(LOGGER, "assessment.form.selected", "Diagnostic form selected",
        Map.of("entityType", "ASSESSMENT_ATTEMPT", "entityId", attempt.id(),
            "learnerId", learnerId,
            "selectionPolicy", DiagnosticFormSelector.SELECTION_POLICY_VERSION,
            "poolSize", form.poolSize(), "itemCount", form.items().size(),
            "skillsCovered", form.skillsCovered(),
            "difficultiesCovered", form.difficultiesCovered(),
            "recentlyPresentedReused", form.recentlyPresentedReused(),
            "outcome", "SUCCESS"));
  }

  /**
   * Assembles the adaptive packet: the full verified pool minus every logical question this
   * learner has ever been shown, ranked per skill by that skill's latest mastery signal.
   *
   * <p>A version with no verified content at all is {@link EmptyItemPoolException} -- V017/V048
   * make that unreachable through publication, so it is a broken invariant. A learner who has
   * exhausted every skill's unseen stock is different: an expected outcome of the no-repeat
   * guarantee working, reported as {@link AssessmentBankExhaustedException} rather than silently
   * recycling a question. Anything in between -- some skills still have unseen stock, others do not
   * -- is a valid, smaller-than-quota packet, logged with which skills came up short rather than
   * refused.
   */
  private void selectAdaptiveForm(AssessmentAttempt attempt, UUID learnerId, UUID assessmentVersionId) {
    AdaptiveSelectionInputs inputs = resolveAdaptiveInputs(learnerId, assessmentVersionId);
    AdaptivePacket packet =
        adaptiveSelector.select(inputs.unseenPool(), inputs.signals(), ThreadLocalRandom.current());
    finishAdaptiveSelection(
        attempt, learnerId, inputs, packet, AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION);
  }

  /**
   * DIAGNOSTIC_SELECTION_V3: the same pool, exposure exclusion, and per-skill signal V2 already
   * resolves via {@link #resolveAdaptiveInputs} -- the only new step is
   * {@link PrerequisiteAwareDiagnosticSelector#adjustForPrerequisites}, applied to the signal map
   * before it reaches the same, unmodified {@link AdaptiveDiagnosticSelector#select}. See
   * {@link PrerequisiteAwareDiagnosticSelector} for why this is a composition, not a rewrite.
   */
  private void selectPrerequisiteAwareForm(
      AssessmentAttempt attempt, UUID learnerId, ResolvedDiagnostic diagnostic) {
    AdaptiveSelectionInputs inputs = resolveAdaptiveInputs(learnerId, diagnostic.assessmentVersionId());

    // Resolved by curriculum_version_id, not (domainCode, versionCode): the assessment version's
    // own version_code ("v1", "v2"...) is a different, unrelated versioning scheme from the
    // curriculum's, and diagnostic.versionCode() here is the former.
    CurriculumGraph graph = curriculumService.graph(inputs.curriculumVersionId());
    Map<String, List<String>> prerequisitesBySkillCode = new LinkedHashMap<>();
    for (CurriculumGraph.SkillNode node : graph.skills()) {
      prerequisitesBySkillCode.put(node.stableCode(), node.prerequisiteSkillCodes());
    }

    Map<String, SkillMasterySignal> adjustedSignals = PrerequisiteAwareDiagnosticSelector
        .adjustForPrerequisites(inputs.signals(), prerequisitesBySkillCode, inputs.statusBySkillCode());

    AdaptivePacket packet =
        adaptiveSelector.select(inputs.unseenPool(), adjustedSignals, ThreadLocalRandom.current());
    finishAdaptiveSelection(
        attempt, learnerId, inputs, packet, PrerequisiteAwareDiagnosticSelector.SELECTION_POLICY_VERSION);
  }

  /**
   * Everything V2 and V3 both need before either calls {@link AdaptiveDiagnosticSelector#select}:
   * the exposure-excluded pool, every in-scope skill's mastery signal, its latest
   * {@link MasteryStatus} (V3 only, but resolved once here rather than a second query pass), and
   * the diagnostic-vs-adaptive-learning phase.
   */
  private AdaptiveSelectionInputs resolveAdaptiveInputs(UUID learnerId, UUID assessmentVersionId) {
    List<AdaptiveEligibleItem> fullPool = repository.findAdaptiveEligibleItems(assessmentVersionId);
    if (fullPool.isEmpty()) {
      throw new EmptyItemPoolException(
          "No verified scoreable items are available to assemble an adaptive form.");
    }

    Set<UUID> exposed = repository.findLearnerExposedLogicalItemIds(learnerId);
    List<AdaptiveEligibleItem> unseenPool = fullPool.stream()
        .filter(item -> !exposed.contains(item.logicalItemId()))
        .toList();

    Map<String, UUID> skillIdByCode = new LinkedHashMap<>();
    for (AdaptiveEligibleItem item : fullPool) {
      skillIdByCode.putIfAbsent(item.skillCode(), item.skillId());
    }

    UUID curriculumVersionId = repository.findCurriculumVersionId(assessmentVersionId)
        .orElseThrow(() -> new IllegalStateException(
            "assessment version has no curriculum version: " + assessmentVersionId));

    Map<String, SkillMasterySignal> signals = new LinkedHashMap<>();
    Map<String, MasteryStatus> statusBySkillCode = new LinkedHashMap<>();
    boolean anySkillHasEvidence = false;
    for (Map.Entry<String, UUID> entry : skillIdByCode.entrySet()) {
      Optional<MasterySnapshot> snapshot =
          masteryRepository.findLatestSnapshot(learnerId, entry.getValue(), curriculumVersionId);
      Optional<SkillMasteryConfig> config =
          masteryRepository.findSkillConfig(entry.getValue(), curriculumVersionId);
      SkillMasterySignal signal = (snapshot.isPresent() && config.isPresent())
          ? SkillMasterySignal.from(snapshot.get(), config.get())
          : SkillMasterySignal.noEvidence();
      if (snapshot.isPresent()) {
        anySkillHasEvidence = true;
        statusBySkillCode.put(entry.getKey(), snapshot.get().status());
      }
      signals.put(entry.getKey(), signal);
    }
    String phase = anySkillHasEvidence ? "ADAPTIVE_LEARNING" : "DIAGNOSTIC_BASELINE";

    return new AdaptiveSelectionInputs(
        unseenPool, skillIdByCode, signals, statusBySkillCode, curriculumVersionId, phase);
  }

  /** Persists the packet (or refuses to, on total exhaustion) and logs the outcome. Shared by V2
   * and V3, which differ only in which selection-policy string is recorded. */
  private void finishAdaptiveSelection(
      AssessmentAttempt attempt, UUID learnerId, AdaptiveSelectionInputs inputs, AdaptivePacket packet,
      String selectionPolicy) {
    if (packet.items().isEmpty()) {
      throw new AssessmentBankExhaustedException(inputs.skillIdByCode().keySet());
    }

    repository.insertSelectedItems(attempt.id(), packet.items());

    Set<String> exhaustedSkills = new LinkedHashSet<>();
    for (String skillCode : inputs.skillIdByCode().keySet()) {
      if (inputs.unseenPool().stream().noneMatch(item -> item.skillCode().equals(skillCode))) {
        exhaustedSkills.add(skillCode);
      }
    }
    boolean underfilled = !packet.skillsWithNoUnseenStock().isEmpty();
    String outcome = underfilled ? "PARTIAL" : "SUCCESS";

    BusinessEventLogger.info(LOGGER, "assessment.form.selected", "Adaptive diagnostic form selected",
        Map.ofEntries(
            Map.entry("entityType", "ASSESSMENT_ATTEMPT"), Map.entry("entityId", attempt.id()),
            Map.entry("learnerId", learnerId),
            Map.entry("selectionPolicy", selectionPolicy),
            Map.entry("packetPolicy", AdaptiveDiagnosticSelector.PACKET_POLICY),
            Map.entry("phase", inputs.phase()),
            Map.entry("poolSize", packet.poolSize()), Map.entry("itemCount", packet.items().size()),
            Map.entry("singleChoiceCount", packet.singleChoiceCount()),
            Map.entry("fillBlankCount", packet.fillBlankCount()),
            Map.entry("skillsCovered", packet.skillsCovered()),
            Map.entry("skillsWithNoUnseenStock", packet.skillsWithNoUnseenStock()),
            Map.entry("bankExhaustedSkills", exhaustedSkills),
            Map.entry("outcome", outcome)));
  }

  private record AdaptiveSelectionInputs(
      List<AdaptiveEligibleItem> unseenPool,
      Map<String, UUID> skillIdByCode,
      Map<String, SkillMasterySignal> signals,
      Map<String, MasteryStatus> statusBySkillCode,
      UUID curriculumVersionId,
      String phase) {
  }

  private UUID parseAttemptId(String rawAttemptId) {
    try {
      return UUID.fromString(rawAttemptId);
    } catch (IllegalArgumentException notAUuid) {
      throw new AttemptNotFoundException(rawAttemptId);
    }
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidIdempotencyKeyException("An Idempotency-Key header is required.");
    }
    String trimmed = idempotencyKey.trim();
    if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new InvalidIdempotencyKeyException(
          "The Idempotency-Key header exceeds " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
    }
    return trimmed;
  }
}
