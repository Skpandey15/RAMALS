package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.assessment.DiagnosticReport.ConceptContext;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceState;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceView;
import io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus;
import io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary;
import io.ramals.learningplatform.assessment.DiagnosticReport.MisconceptionFinding;
import io.ramals.learningplatform.assessment.DiagnosticReport.ObjectiveContext;
import io.ramals.learningplatform.assessment.DiagnosticReport.ReportMode;
import io.ramals.learningplatform.assessment.DiagnosticReport.SubConceptContext;
import io.ramals.learningplatform.assessment.DiagnosticReportRepository.DiagnosticNodeRow;
import io.ramals.learningplatform.assessment.DiagnosticReportRepository.EvidenceCountRow;
import io.ramals.learningplatform.assessment.DiagnosticReportRepository.MisconceptionContextRow;
import io.ramals.learningplatform.assessment.DiagnosticReportRepository.ObjectiveContextRow;
import io.ramals.learningplatform.assessment.MisconceptionConfidenceRepository.ProvenanceLink;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import io.ramals.learningplatform.mastery.MasteryMapEntry;
import io.ramals.learningplatform.mastery.MasteryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2-ADR-029 (H6): composes {@link DiagnosticReport} on read from governed facts G2/G3/ontology/
 * mastery already persist -- no new diagnosis logic, no persisted report artifact, and never a call
 * to {@link DiagnosticConfidenceCalculatorV1} (a finding with no persisted G3 snapshot is reported as
 * {@link ConfidenceState#NOT_ASSESSED}, verbatim, not synthesized).
 *
 * <p>Two report identities, assembled by similar but distinct logic below, since their candidate
 * sets and their relationship to mastery are genuinely different, not merely differently scoped:
 *
 * <ul>
 *   <li>{@link #currentDomainReport}/{@link #currentDomainReportForLearner} -- every misconception
 *       with evidence in one domain, each at its own latest G3 state (or {@code NOT_ASSESSED} if
 *       none exists), plus current mastery context.
 *   <li>{@link #attemptReport}/{@link #attemptReportForLearner} -- only the misconceptions one exact
 *       attempt's own responses produced G2 evidence for (never derived from which misconceptions
 *       have a G3 snapshot, which would silently conflate "no snapshot" with "no evidence"), each
 *       {@code ASSESSED} from that attempt's own persisted snapshot when one exists or {@code
 *       NOT_ASSESSED} with that attempt's own exact evidence counts when none does -- and carrying
 *       no mastery at all: an Attempt Diagnostic Report is exact-attempt diagnostic findings only,
 *       never silently combined with today's mastery state, and H6 V1 does not reconstruct mastery
 *       as it stood at that attempt's own completion.
 * </ul>
 *
 * <p>Read-only throughout ({@code @Transactional(readOnly = true)}); writes nothing, ever. Query
 * count is batched and roughly constant in the number of findings, not proportional to it.
 */
@Service
public class DiagnosticReportService {

  private final AssessmentRepository assessmentRepository;
  private final LearnerService learnerService;
  private final DiagnosticReportRepository reportRepository;
  private final MisconceptionConfidenceRepository confidenceRepository;
  private final MasteryRepository masteryRepository;

  public DiagnosticReportService(
      AssessmentRepository assessmentRepository,
      LearnerService learnerService,
      DiagnosticReportRepository reportRepository,
      MisconceptionConfidenceRepository confidenceRepository,
      MasteryRepository masteryRepository) {
    this.assessmentRepository = assessmentRepository;
    this.learnerService = learnerService;
    this.reportRepository = reportRepository;
    this.confidenceRepository = confidenceRepository;
    this.masteryRepository = masteryRepository;
  }

  /** Current Domain Diagnostic Report for the authenticated learner. A learner with no record at
   * all yields an empty report -- the same convention {@code MasteryMapService.masteryMap} and
   * {@code GapDiagnosisService.diagnose} already use. */
  @Transactional(readOnly = true)
  public DiagnosticReport currentDomainReport(String subject, String domainCode) {
    String normalizedDomain = requireDiagnostic(domainCode);
    return learnerService.findLearner(subject)
        .map(learner -> buildCurrentDomainReport(learner.id(), normalizedDomain))
        .orElseGet(() -> emptyCurrentDomainReport(null, normalizedDomain, null));
  }

  /** Admin equivalent: {@code learnerId} is already known, supplied by the caller. */
  @Transactional(readOnly = true)
  public DiagnosticReport currentDomainReportForLearner(UUID learnerId, String domainCode) {
    String normalizedDomain = requireDiagnostic(domainCode);
    return buildCurrentDomainReport(learnerId, normalizedDomain);
  }

  private String requireDiagnostic(String domainCode) {
    String normalizedDomain = domainCode.toUpperCase(Locale.ROOT);
    assessmentRepository.findPublishedDiagnostic(normalizedDomain)
        .orElseThrow(() -> new DiagnosticNotFoundException(normalizedDomain));
    return normalizedDomain;
  }

  /** Attempt Diagnostic Report for the authenticated learner -- the findings that exact attempt
   * produced, never "learner state as of this attempt" (M2-ADR-029 §C). An attempt that does not
   * exist, or does not belong to this learner, is reported identically (never disclosing which),
   * matching {@code DiagnosticService.getAttempt}'s own convention. */
  @Transactional(readOnly = true)
  public DiagnosticReport attemptReport(String subject, String rawAttemptId) {
    Learner learner = learnerService.findLearner(subject)
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    UUID attemptId = parseAttemptId(rawAttemptId);
    AssessmentAttempt attempt = assessmentRepository.findAttempt(attemptId)
        .filter(candidate -> candidate.learnerId().equals(learner.id()))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    return buildAttemptReport(attempt);
  }

  /** Admin equivalent: {@code learnerId} is already known, supplied by the caller; ownership is
   * still checked against it, the same non-disclosure convention. */
  @Transactional(readOnly = true)
  public DiagnosticReport attemptReportForLearner(UUID learnerId, String rawAttemptId) {
    UUID attemptId = parseAttemptId(rawAttemptId);
    AssessmentAttempt attempt = assessmentRepository.findAttempt(attemptId)
        .filter(candidate -> candidate.learnerId().equals(learnerId))
        .orElseThrow(() -> new AttemptNotFoundException(rawAttemptId));
    return buildAttemptReport(attempt);
  }

  // -------------------------------------------------------------------------------------------
  // Current Domain Diagnostic Report
  // -------------------------------------------------------------------------------------------

  private DiagnosticReport buildCurrentDomainReport(UUID learnerId, String domainCode) {
    String normalizedDomain = domainCode.toUpperCase(Locale.ROOT);
    ResolvedDiagnostic diagnostic = assessmentRepository.findPublishedDiagnostic(normalizedDomain)
        .orElseThrow(() -> new DiagnosticNotFoundException(normalizedDomain));
    UUID curriculumVersionId =
        assessmentRepository.findCurriculumVersionId(diagnostic.assessmentVersionId()).orElse(null);

    List<UUID> candidateIds = reportRepository.findMisconceptionIdsWithEvidence(learnerId);
    if (candidateIds.isEmpty()) {
      return emptyCurrentDomainReport(learnerId, normalizedDomain, curriculumVersionId);
    }

    Map<UUID, MisconceptionContextRow> misconceptionById = index(
        reportRepository.findMisconceptionContext(candidateIds), MisconceptionContextRow::id);
    AncestryResolution ancestry = resolveAncestry(misconceptionById.values());

    List<UUID> inDomainIds = candidateIds.stream()
        .filter(id -> {
          ResolvedTarget target = ancestry.targetByMisconceptionId().get(id);
          ObjectiveContextRow objective = target == null ? null
              : ancestry.objectiveContextById().get(target.objectiveId());
          return objective != null && normalizedDomain.equalsIgnoreCase(objective.domainCode());
        })
        .toList();

    if (inDomainIds.isEmpty()) {
      return emptyCurrentDomainReport(learnerId, normalizedDomain, curriculumVersionId);
    }

    Map<UUID, MisconceptionConfidenceObservation> snapshotByMisconceptionId = index(
        confidenceRepository.findLatestForLearner(learnerId),
        MisconceptionConfidenceObservation::misconceptionId);

    List<UUID> notAssessedIds = inDomainIds.stream()
        .filter(id -> !snapshotByMisconceptionId.containsKey(id))
        .toList();
    Map<UUID, EvidenceSummary> liveCountsByMisconceptionId =
        groupEvidenceCounts(reportRepository.findEvidenceCounts(learnerId, notAssessedIds));

    Map<UUID, List<UUID>> provenanceBySnapshotId = groupProvenance(
        confidenceRepository.findProvenanceForSnapshots(snapshotIdsOf(inDomainIds, snapshotByMisconceptionId)));

    List<MisconceptionFinding> findings = inDomainIds.stream()
        .map(id -> buildFinding(
            misconceptionById.get(id), ancestry, snapshotByMisconceptionId.get(id),
            liveCountsByMisconceptionId.get(id), provenanceBySnapshotId))
        .toList();

    List<MasteryMapEntry> mastery = latestMasteryMap(learnerId, curriculumVersionId);

    return new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, learnerId, normalizedDomain, null, Instant.now(),
        DiagnosticDataStatus.HAS_EVIDENCE, findings, mastery);
  }

  private DiagnosticReport emptyCurrentDomainReport(
      UUID learnerId, String normalizedDomain, UUID curriculumVersionId) {
    List<MasteryMapEntry> mastery = latestMasteryMap(learnerId, curriculumVersionId);
    return new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, learnerId, normalizedDomain, null, Instant.now(),
        DiagnosticDataStatus.NO_EVIDENCE, List.of(), mastery);
  }

  // -------------------------------------------------------------------------------------------
  // Attempt Diagnostic Report
  // -------------------------------------------------------------------------------------------

  private DiagnosticReport buildAttemptReport(AssessmentAttempt attempt) {
    // The candidate set is a G2 question -- every misconception THIS attempt's own responses
    // produced MISCONCEPTION_EVIDENCE_V1 evidence for -- never derived from
    // core.misconception_confidence_observation, which would silently conflate "no G3 snapshot yet"
    // with "no G2 evidence at all" (M2-ADR-029 §B/§H).
    List<UUID> candidateIds = reportRepository.findMisconceptionIdsWithEvidenceForAttempt(attempt.id());
    if (candidateIds.isEmpty()) {
      return new DiagnosticReport(
          ReportMode.ATTEMPT, attempt.learnerId(), null, attempt.id(), Instant.now(),
          DiagnosticDataStatus.NO_EVIDENCE, List.of(), List.of());
    }

    Map<UUID, MisconceptionContextRow> misconceptionById = index(
        reportRepository.findMisconceptionContext(candidateIds), MisconceptionContextRow::id);
    AncestryResolution ancestry = resolveAncestry(misconceptionById.values());

    // A candidate is ASSESSED iff this exact attempt also wrote a confidence snapshot for it (G3
    // recomputes every misconception it evidenced, in the same transaction -- M2-ADR-028 §4 -- so
    // this is the normal case; a candidate here with no matching snapshot is NOT_ASSESSED, e.g.
    // evidence captured before G3 existed, or a defect, never silently promoted to a computed band).
    Map<UUID, MisconceptionConfidenceObservation> snapshotByMisconceptionId = index(
        confidenceRepository.findAllForAttempt(attempt.id()),
        MisconceptionConfidenceObservation::misconceptionId);

    List<UUID> notAssessedIds = candidateIds.stream()
        .filter(id -> !snapshotByMisconceptionId.containsKey(id))
        .toList();
    // Exact-attempt counts, never the learner's wider evidence across other attempts (M2-ADR-029 §B).
    Map<UUID, EvidenceSummary> liveCountsByMisconceptionId = groupEvidenceCounts(
        reportRepository.findEvidenceCountsForAttempt(attempt.id(), notAssessedIds));

    Map<UUID, List<UUID>> provenanceBySnapshotId = groupProvenance(
        confidenceRepository.findProvenanceForSnapshots(
            snapshotIdsOf(candidateIds, snapshotByMisconceptionId)));

    List<MisconceptionFinding> findings = candidateIds.stream()
        .map(id -> buildFinding(
            misconceptionById.get(id), ancestry, snapshotByMisconceptionId.get(id),
            liveCountsByMisconceptionId.get(id), provenanceBySnapshotId))
        .toList();

    // No mastery: an Attempt Diagnostic Report is exact-attempt diagnostic findings only (M2-ADR-029
    // §Mastery) -- it never combines an older attempt's own findings with today's mastery state, and
    // H6 V1 does not attempt to reconstruct mastery as it stood at this attempt's own completion.
    return new DiagnosticReport(
        ReportMode.ATTEMPT, attempt.learnerId(), null, attempt.id(), Instant.now(),
        DiagnosticDataStatus.HAS_EVIDENCE, findings, List.of());
  }

  // -------------------------------------------------------------------------------------------
  // Shared assembly
  // -------------------------------------------------------------------------------------------

  private MisconceptionFinding buildFinding(
      MisconceptionContextRow misconception, AncestryResolution ancestry,
      MisconceptionConfidenceObservation snapshot, EvidenceSummary liveCounts,
      Map<UUID, List<UUID>> provenanceBySnapshotId) {
    ResolvedTarget target = ancestry.targetByMisconceptionId().get(misconception.id());
    ObjectiveContextRow objectiveRow = ancestry.objectiveContextById().get(target.objectiveId());
    ObjectiveContext objectiveContext = objectiveRow == null ? null
        : new ObjectiveContext(objectiveRow.objectiveId(), objectiveRow.objectiveCode(),
            objectiveRow.description());

    if (snapshot != null) {
      List<UUID> provenance = provenanceBySnapshotId.getOrDefault(snapshot.id(), List.of());
      return new MisconceptionFinding(
          misconception.id(), misconception.name(), misconception.description(),
          target.targetType(), target.targetId(), objectiveContext, target.concept(),
          target.subConcept(),
          new EvidenceSummary(snapshot.supportingCount(), snapshot.contradictoryCount(),
              snapshot.inconclusiveCount()),
          ConfidenceState.ASSESSED,
          new ConfidenceView(snapshot.band(), snapshot.policyVersion(), snapshot.createdAt()),
          snapshot.id(), snapshot.attemptId(), provenance);
    }

    EvidenceSummary counts = liveCounts != null ? liveCounts : new EvidenceSummary(0, 0, 0);
    return new MisconceptionFinding(
        misconception.id(), misconception.name(), misconception.description(),
        target.targetType(), target.targetId(), objectiveContext, target.concept(),
        target.subConcept(), counts, ConfidenceState.NOT_ASSESSED, null, null, null, List.of());
  }

  /**
   * Walks each misconception's own exclusive-arc target out to its objective, resolving a
   * SUB_CONCEPT one hop further to its own parent CONCEPT -- at most two hops, matching the ontology's
   * own "no third nesting level" rule. Batched: at most two extra {@code diagnostic_node} reads and
   * one {@code learning_objective} read, regardless of how many misconceptions are being resolved.
   */
  private AncestryResolution resolveAncestry(Collection<MisconceptionContextRow> misconceptions) {
    Set<UUID> directNodeIds = new HashSet<>();
    for (MisconceptionContextRow row : misconceptions) {
      if (row.targetDiagnosticNodeId() != null) {
        directNodeIds.add(row.targetDiagnosticNodeId());
      }
    }
    Map<UUID, DiagnosticNodeRow> nodeById = new HashMap<>(
        index(reportRepository.findDiagnosticNodes(directNodeIds), DiagnosticNodeRow::id));

    Set<UUID> parentNodeIds = new HashSet<>();
    for (DiagnosticNodeRow node : nodeById.values()) {
      if (node.nodeType() == DiagnosticNodeType.SUB_CONCEPT && node.parentNodeId() != null) {
        parentNodeIds.add(node.parentNodeId());
      }
    }
    nodeById.putAll(index(reportRepository.findDiagnosticNodes(parentNodeIds), DiagnosticNodeRow::id));

    Map<UUID, ResolvedTarget> targetByMisconceptionId = new HashMap<>();
    Set<UUID> objectiveIds = new HashSet<>();
    for (MisconceptionContextRow row : misconceptions) {
      ResolvedTarget target = resolveTarget(row, nodeById);
      targetByMisconceptionId.put(row.id(), target);
      if (target.objectiveId() != null) {
        objectiveIds.add(target.objectiveId());
      }
    }

    Map<UUID, ObjectiveContextRow> objectiveContextById =
        index(reportRepository.findObjectiveContext(objectiveIds), ObjectiveContextRow::objectiveId);
    return new AncestryResolution(targetByMisconceptionId, objectiveContextById);
  }

  private ResolvedTarget resolveTarget(MisconceptionContextRow row, Map<UUID, DiagnosticNodeRow> nodeById) {
    if (row.targetObjectiveId() != null) {
      return new ResolvedTarget(
          MisconceptionTargetType.LEARNING_OBJECTIVE, row.targetObjectiveId(),
          row.targetObjectiveId(), null, null);
    }
    DiagnosticNodeRow node = nodeById.get(row.targetDiagnosticNodeId());
    if (node.nodeType() == DiagnosticNodeType.CONCEPT) {
      return new ResolvedTarget(
          MisconceptionTargetType.CONCEPT, node.id(), node.objectiveId(),
          new ConceptContext(node.id(), node.name()), null);
    }
    DiagnosticNodeRow parent = nodeById.get(node.parentNodeId());
    return new ResolvedTarget(
        MisconceptionTargetType.SUB_CONCEPT, node.id(), parent.objectiveId(),
        new ConceptContext(parent.id(), parent.name()),
        new SubConceptContext(node.id(), node.name()));
  }

  private List<MasteryMapEntry> latestMasteryMap(UUID learnerId, UUID curriculumVersionId) {
    if (learnerId == null || curriculumVersionId == null) {
      return List.of();
    }
    return masteryRepository.latestMasteryMap(learnerId, curriculumVersionId);
  }

  private static List<UUID> snapshotIdsOf(
      Collection<UUID> misconceptionIds, Map<UUID, MisconceptionConfidenceObservation> byMisconceptionId) {
    List<UUID> ids = new ArrayList<>();
    for (UUID misconceptionId : misconceptionIds) {
      MisconceptionConfidenceObservation snapshot = byMisconceptionId.get(misconceptionId);
      if (snapshot != null) {
        ids.add(snapshot.id());
      }
    }
    return ids;
  }

  private static Map<UUID, EvidenceSummary> groupEvidenceCounts(List<EvidenceCountRow> rows) {
    Map<UUID, int[]> counts = new HashMap<>();
    for (EvidenceCountRow row : rows) {
      int[] triple = counts.computeIfAbsent(row.misconceptionId(), ignored -> new int[3]);
      switch (row.outcome()) {
        case SUPPORTING -> triple[0] += row.count();
        case CONTRADICTORY -> triple[1] += row.count();
        case INCONCLUSIVE -> triple[2] += row.count();
      }
    }
    Map<UUID, EvidenceSummary> result = new HashMap<>();
    counts.forEach((id, triple) -> result.put(id, new EvidenceSummary(triple[0], triple[1], triple[2])));
    return result;
  }

  private static Map<UUID, List<UUID>> groupProvenance(List<ProvenanceLink> links) {
    Map<UUID, List<UUID>> byConfidenceObservationId = new HashMap<>();
    for (ProvenanceLink link : links) {
      byConfidenceObservationId
          .computeIfAbsent(link.confidenceObservationId(), ignored -> new ArrayList<>())
          .add(link.evidenceObservationId());
    }
    return byConfidenceObservationId;
  }

  private static <T> Map<UUID, T> index(Collection<T> values, Function<T, UUID> keyFn) {
    Map<UUID, T> result = new HashMap<>();
    for (T value : values) {
      result.put(keyFn.apply(value), value);
    }
    return result;
  }

  private UUID parseAttemptId(String rawAttemptId) {
    try {
      return UUID.fromString(rawAttemptId);
    } catch (IllegalArgumentException notAUuid) {
      throw new AttemptNotFoundException(rawAttemptId);
    }
  }

  /** One misconception's resolved target: which level, its own id, the objective it ultimately
   * belongs to, and display-only concept/sub-concept ancestry (both {@code null} for a
   * LEARNING_OBJECTIVE target; only {@code subConcept} null for a CONCEPT target). */
  private record ResolvedTarget(
      MisconceptionTargetType targetType, UUID targetId, UUID objectiveId,
      ConceptContext concept, SubConceptContext subConcept) {
  }

  private record AncestryResolution(
      Map<UUID, ResolvedTarget> targetByMisconceptionId,
      Map<UUID, ObjectiveContextRow> objectiveContextById) {
  }
}
