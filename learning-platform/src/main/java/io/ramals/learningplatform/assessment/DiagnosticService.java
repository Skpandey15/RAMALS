package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasteryRepository;
import io.ramals.learningplatform.mastery.MasterySnapshot;
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

  public DiagnosticService(
      AssessmentRepository repository,
      LearnerService learnerService,
      DiagnosticFormSelector selector,
      AdaptiveDiagnosticSelector adaptiveSelector,
      MasteryRepository masteryRepository) {
    this.repository = repository;
    this.learnerService = learnerService;
    this.selector = selector;
    this.adaptiveSelector = adaptiveSelector;
    this.masteryRepository = masteryRepository;
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
      String packetPolicy = AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION.equals(selectionPolicy)
          ? AdaptiveDiagnosticSelector.PACKET_POLICY
          : null;
      AssessmentAttempt created =
          repository.insertAttempt(learner.id(), versionId, key, selectionPolicy, packetPolicy);
      selectForm(created, learner.id(), versionId, selectionPolicy);
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
      AssessmentAttempt attempt, UUID learnerId, UUID assessmentVersionId, String selectionPolicy) {
    if (AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION.equals(selectionPolicy)) {
      selectAdaptiveForm(attempt, learnerId, assessmentVersionId);
    } else {
      selectLegacyForm(attempt, learnerId, assessmentVersionId);
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
      }
      signals.put(entry.getKey(), signal);
    }
    String phase = anySkillHasEvidence ? "ADAPTIVE_LEARNING" : "DIAGNOSTIC_BASELINE";

    AdaptivePacket packet = adaptiveSelector.select(unseenPool, signals, ThreadLocalRandom.current());

    if (packet.items().isEmpty()) {
      throw new AssessmentBankExhaustedException(skillIdByCode.keySet());
    }

    repository.insertSelectedItems(attempt.id(), packet.items());

    Set<String> exhaustedSkills = new LinkedHashSet<>();
    for (String skillCode : skillIdByCode.keySet()) {
      if (unseenPool.stream().noneMatch(item -> item.skillCode().equals(skillCode))) {
        exhaustedSkills.add(skillCode);
      }
    }
    boolean underfilled = !packet.skillsWithNoUnseenStock().isEmpty();
    String outcome = underfilled ? "PARTIAL" : "SUCCESS";

    BusinessEventLogger.info(LOGGER, "assessment.form.selected", "Adaptive diagnostic form selected",
        Map.ofEntries(
            Map.entry("entityType", "ASSESSMENT_ATTEMPT"), Map.entry("entityId", attempt.id()),
            Map.entry("learnerId", learnerId),
            Map.entry("selectionPolicy", AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION),
            Map.entry("packetPolicy", AdaptiveDiagnosticSelector.PACKET_POLICY),
            Map.entry("phase", phase),
            Map.entry("poolSize", packet.poolSize()), Map.entry("itemCount", packet.items().size()),
            Map.entry("singleChoiceCount", packet.singleChoiceCount()),
            Map.entry("fillBlankCount", packet.fillBlankCount()),
            Map.entry("skillsCovered", packet.skillsCovered()),
            Map.entry("skillsWithNoUnseenStock", packet.skillsWithNoUnseenStock()),
            Map.entry("bankExhaustedSkills", exhaustedSkills),
            Map.entry("outcome", outcome)));
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
