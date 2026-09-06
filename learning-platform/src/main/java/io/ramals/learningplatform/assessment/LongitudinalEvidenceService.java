package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.assessment.DiagnosticReport.ConceptContext;
import io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary;
import io.ramals.learningplatform.assessment.DiagnosticReport.ObjectiveContext;
import io.ramals.learningplatform.assessment.DiagnosticReport.SubConceptContext;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport.Baseline;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport.LatestConfidence;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport.LongitudinalEvidenceFinding;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceRepository.DiagnosticNodeRow;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceRepository.EvidenceObservationRow;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceRepository.MisconceptionContextRow;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceRepository.ObjectiveContextRow;
import io.ramals.learningplatform.assessment.MisconceptionConfidenceRepository.ProvenanceLink;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
 * M2-ADR-030 (H7): composes {@link LongitudinalEvidenceReport} on read from governed G2/G3/ontology
 * facts already persisted -- no new persisted state, no recomputation of G3, and never a call to
 * {@link DiagnosticConfidenceCalculatorV1}. Deliberately independent of H6's own {@code
 * DiagnosticReportService}/{@code DiagnosticReportRepository} (see {@link
 * LongitudinalEvidenceRepository}'s own javadoc): this milestone's boundary is H6 untouched, zero
 * shared surface, so ontology/context resolution below is a small, accepted duplication of {@code
 * DiagnosticReportService.resolveAncestry}/{@code resolveTarget}, not a reuse.
 *
 * <p>Two identically-assembled uses -- {@link #domainSummary}/{@link #domainSummaryForLearner} (every
 * misconception with {@link LongitudinalDataStatus#HAS_BASELINE} in one domain) and {@link
 * #misconceptionDetail}/{@link #misconceptionDetailForLearner} (exactly one misconception, present
 * even when {@link LongitudinalDataStatus#NO_BASELINE}) -- sharing the same {@link #buildFinding}
 * assembly. Read-only throughout ({@code @Transactional(readOnly = true)}); writes nothing, ever.
 */
@Service
public class LongitudinalEvidenceService {

  private final AssessmentRepository assessmentRepository;
  private final LearnerService learnerService;
  private final LongitudinalEvidenceRepository repository;
  private final MisconceptionConfidenceRepository confidenceRepository;
  private final LongitudinalEvidencePolicyV1 policy;

  public LongitudinalEvidenceService(
      AssessmentRepository assessmentRepository,
      LearnerService learnerService,
      LongitudinalEvidenceRepository repository,
      MisconceptionConfidenceRepository confidenceRepository,
      LongitudinalEvidencePolicyV1 policy) {
    this.assessmentRepository = assessmentRepository;
    this.learnerService = learnerService;
    this.repository = repository;
    this.confidenceRepository = confidenceRepository;
    this.policy = policy;
  }

  /** Domain summary for the authenticated learner. A learner with no record at all yields an empty
   * report, mirroring {@code DiagnosticReportService.currentDomainReport}'s own convention. */
  @Transactional(readOnly = true)
  public LongitudinalEvidenceReport domainSummary(String subject, String domainCode) {
    String normalizedDomain = requireDiagnostic(domainCode);
    return learnerService.findLearner(subject)
        .map(learner -> buildDomainSummary(learner.id(), normalizedDomain))
        .orElseGet(() -> new LongitudinalEvidenceReport(null, normalizedDomain, Instant.now(), List.of()));
  }

  /** Admin equivalent: {@code learnerId} is already known, supplied by the caller. */
  @Transactional(readOnly = true)
  public LongitudinalEvidenceReport domainSummaryForLearner(UUID learnerId, String domainCode) {
    String normalizedDomain = requireDiagnostic(domainCode);
    return buildDomainSummary(learnerId, normalizedDomain);
  }

  /** Single-misconception detail for the authenticated learner. Present (200) even when the learner
   * has no eligible baseline yet -- {@link LongitudinalDataStatus#NO_BASELINE} is a real, valid
   * answer, never a 404. A learner with no record at all is treated identically to one who exists but
   * has no evidence, same convention as {@link #domainSummary}. */
  @Transactional(readOnly = true)
  public LongitudinalEvidenceReport misconceptionDetail(String subject, String rawMisconceptionId) {
    UUID misconceptionId = parseMisconceptionId(rawMisconceptionId);
    UUID learnerId = learnerService.findLearner(subject).map(Learner::id).orElse(null);
    return buildDetail(learnerId, misconceptionId);
  }

  /** Admin equivalent: {@code learnerId} is already known, supplied by the caller. */
  @Transactional(readOnly = true)
  public LongitudinalEvidenceReport misconceptionDetailForLearner(UUID learnerId, String rawMisconceptionId) {
    UUID misconceptionId = parseMisconceptionId(rawMisconceptionId);
    return buildDetail(learnerId, misconceptionId);
  }

  private String requireDiagnostic(String domainCode) {
    String normalizedDomain = domainCode.toUpperCase(Locale.ROOT);
    assessmentRepository.findPublishedDiagnostic(normalizedDomain)
        .orElseThrow(() -> new DiagnosticNotFoundException(normalizedDomain));
    return normalizedDomain;
  }

  private UUID parseMisconceptionId(String rawMisconceptionId) {
    try {
      return UUID.fromString(rawMisconceptionId);
    } catch (IllegalArgumentException notAUuid) {
      throw new MisconceptionNotFoundException(rawMisconceptionId);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Domain summary -- HAS_BASELINE findings only (M2-ADR-030 §1), filtered to the requested domain.
  // -------------------------------------------------------------------------------------------

  private LongitudinalEvidenceReport buildDomainSummary(UUID learnerId, String domainCode) {
    if (learnerId == null) {
      return new LongitudinalEvidenceReport(null, domainCode, Instant.now(), List.of());
    }
    List<MisconceptionConfidenceObservation> baselines =
        repository.findEarliestBaselineSnapshotsForLearner(learnerId);
    if (baselines.isEmpty()) {
      return new LongitudinalEvidenceReport(learnerId, domainCode, Instant.now(), List.of());
    }
    Map<UUID, MisconceptionConfidenceObservation> baselineByMisconceptionId =
        index(baselines, MisconceptionConfidenceObservation::misconceptionId);
    List<UUID> candidateIds = new ArrayList<>(baselineByMisconceptionId.keySet());

    Map<UUID, MisconceptionContextRow> misconceptionById = index(
        repository.findMisconceptionContext(candidateIds), MisconceptionContextRow::id);
    AncestryResolution ancestry = resolveAncestry(misconceptionById.values());

    List<UUID> inDomainIds = candidateIds.stream()
        .filter(id -> misconceptionById.containsKey(id) && isInDomain(id, ancestry, domainCode))
        .toList();
    if (inDomainIds.isEmpty()) {
      return new LongitudinalEvidenceReport(learnerId, domainCode, Instant.now(), List.of());
    }

    List<LongitudinalEvidenceFinding> findings =
        buildFindings(learnerId, inDomainIds, misconceptionById, ancestry, baselineByMisconceptionId);
    return new LongitudinalEvidenceReport(learnerId, domainCode, Instant.now(), findings);
  }

  // -------------------------------------------------------------------------------------------
  // Single-misconception detail -- present even when NO_BASELINE.
  // -------------------------------------------------------------------------------------------

  private LongitudinalEvidenceReport buildDetail(UUID learnerId, UUID misconceptionId) {
    List<MisconceptionContextRow> rows = repository.findMisconceptionContext(List.of(misconceptionId));
    if (rows.isEmpty()) {
      throw new MisconceptionNotFoundException(misconceptionId.toString());
    }
    Map<UUID, MisconceptionContextRow> misconceptionById = index(rows, MisconceptionContextRow::id);
    AncestryResolution ancestry = resolveAncestry(rows);

    if (learnerId == null) {
      LongitudinalEvidenceFinding finding = buildFinding(
          misconceptionById.get(misconceptionId), ancestry, null, List.of(), null, Map.of());
      return new LongitudinalEvidenceReport(null, null, Instant.now(), List.of(finding));
    }

    Map<UUID, MisconceptionConfidenceObservation> baselineByMisconceptionId = index(
        repository.findEarliestBaselineSnapshotsForLearner(learnerId).stream()
            .filter(row -> row.misconceptionId().equals(misconceptionId))
            .toList(),
        MisconceptionConfidenceObservation::misconceptionId);

    List<LongitudinalEvidenceFinding> findings = buildFindings(
        learnerId, List.of(misconceptionId), misconceptionById, ancestry, baselineByMisconceptionId);
    return new LongitudinalEvidenceReport(learnerId, null, Instant.now(), findings);
  }

  // -------------------------------------------------------------------------------------------
  // Shared assembly.
  // -------------------------------------------------------------------------------------------

  private List<LongitudinalEvidenceFinding> buildFindings(
      UUID learnerId, Collection<UUID> misconceptionIds, Map<UUID, MisconceptionContextRow> misconceptionById,
      AncestryResolution ancestry, Map<UUID, MisconceptionConfidenceObservation> baselineByMisconceptionId) {

    Map<UUID, List<EvidenceObservationRow>> evidenceByMisconceptionId = groupEvidence(
        repository.findAllEvidenceForLearner(learnerId, misconceptionIds));

    Map<UUID, MisconceptionConfidenceObservation> latestByMisconceptionId = index(
        confidenceRepository.findLatestForLearner(learnerId),
        MisconceptionConfidenceObservation::misconceptionId);

    Set<UUID> snapshotIds = new HashSet<>();
    for (UUID id : misconceptionIds) {
      MisconceptionConfidenceObservation baseline = baselineByMisconceptionId.get(id);
      if (baseline != null) {
        snapshotIds.add(baseline.id());
      }
      MisconceptionConfidenceObservation latest = latestByMisconceptionId.get(id);
      if (latest != null) {
        snapshotIds.add(latest.id());
      }
    }
    Map<UUID, List<UUID>> provenanceBySnapshotId =
        groupProvenance(confidenceRepository.findProvenanceForSnapshots(snapshotIds));

    List<LongitudinalEvidenceFinding> findings = new ArrayList<>();
    for (UUID id : misconceptionIds) {
      findings.add(buildFinding(
          misconceptionById.get(id), ancestry, baselineByMisconceptionId.get(id),
          evidenceByMisconceptionId.getOrDefault(id, List.of()), latestByMisconceptionId.get(id),
          provenanceBySnapshotId));
    }
    return findings;
  }

  private LongitudinalEvidenceFinding buildFinding(
      MisconceptionContextRow misconception, AncestryResolution ancestry,
      MisconceptionConfidenceObservation baselineRow, List<EvidenceObservationRow> allEvidence,
      MisconceptionConfidenceObservation latestSnapshot, Map<UUID, List<UUID>> provenanceBySnapshotId) {

    ResolvedTarget target = ancestry.targetByMisconceptionId().get(misconception.id());
    ObjectiveContextRow objectiveRow = ancestry.objectiveContextById().get(target.objectiveId());
    ObjectiveContext objectiveContext = objectiveRow == null ? null
        : new ObjectiveContext(objectiveRow.objectiveId(), objectiveRow.objectiveCode(),
            objectiveRow.description());

    LatestConfidence latestConfidence = latestSnapshot == null ? null
        : new LatestConfidence(latestSnapshot.id(), latestSnapshot.attemptId(), latestSnapshot.band(),
            latestSnapshot.createdAt());
    ConfidenceCoverage confidenceCoverage = latestSnapshot == null ? null
        : computeCoverage(allEvidence, provenanceBySnapshotId.getOrDefault(latestSnapshot.id(), List.of()));

    if (baselineRow == null) {
      return new LongitudinalEvidenceFinding(
          misconception.id(), misconception.name(), misconception.description(),
          target.targetType(), target.targetId(), objectiveContext, target.concept(), target.subConcept(),
          LongitudinalDataStatus.NO_BASELINE, null, null, new EvidenceSummary(0, 0, 0), List.of(),
          latestConfidence, confidenceCoverage, null);
    }

    Set<UUID> baselineProvenanceIds = new HashSet<>(
        provenanceBySnapshotId.getOrDefault(baselineRow.id(), List.of()));
    List<EvidenceObservationRow> laterRows = allEvidence.stream()
        .filter(row -> !baselineProvenanceIds.contains(row.id()))
        // Deterministic presentation ordering only (M2-ADR-030 §5) -- created_at is fixed per
        // PostgreSQL transaction, and UuidV7's own tiebreak bits are drawn from SecureRandom with no
        // monotonic counter, so this is never read as causal/generation order when both tie.
        .sorted(Comparator.comparing(EvidenceObservationRow::createdAt)
            .thenComparing(EvidenceObservationRow::id))
        .toList();

    int supporting = 0;
    int contradictory = 0;
    int inconclusive = 0;
    for (EvidenceObservationRow row : laterRows) {
      switch (row.outcome()) {
        case SUPPORTING -> supporting++;
        case CONTRADICTORY -> contradictory++;
        case INCONCLUSIVE -> inconclusive++;
      }
    }
    LongitudinalEvidenceState state = policy.classify(supporting, contradictory, inconclusive);

    return new LongitudinalEvidenceFinding(
        misconception.id(), misconception.name(), misconception.description(),
        target.targetType(), target.targetId(), objectiveContext, target.concept(), target.subConcept(),
        LongitudinalDataStatus.HAS_BASELINE,
        new Baseline(baselineRow.id(), baselineRow.band(), baselineRow.createdAt()),
        state,
        new EvidenceSummary(supporting, contradictory, inconclusive),
        laterRows.stream().map(EvidenceObservationRow::id).toList(),
        latestConfidence, confidenceCoverage, LongitudinalEvidencePolicyV1.POLICY_VERSION);
  }

  /** {@code CURRENT} iff every evidence row for the pair is cited by the latest snapshot's own
   * provenance; {@code STALE_RELATIVE_TO_LATER_EVIDENCE} otherwise. Determined by exact provenance
   * ids, never timestamps (M2-ADR-030 §6). Only called when a latest snapshot exists. */
  private static ConfidenceCoverage computeCoverage(
      List<EvidenceObservationRow> allEvidence, List<UUID> latestProvenanceIds) {
    Set<UUID> covered = new HashSet<>(latestProvenanceIds);
    boolean allCovered = allEvidence.stream().allMatch(row -> covered.contains(row.id()));
    return allCovered ? ConfidenceCoverage.CURRENT : ConfidenceCoverage.STALE_RELATIVE_TO_LATER_EVIDENCE;
  }

  private boolean isInDomain(UUID misconceptionId, AncestryResolution ancestry, String domainCode) {
    ResolvedTarget target = ancestry.targetByMisconceptionId().get(misconceptionId);
    if (target == null) {
      return false;
    }
    ObjectiveContextRow objective = ancestry.objectiveContextById().get(target.objectiveId());
    return objective != null && domainCode.equalsIgnoreCase(objective.domainCode());
  }

  /**
   * Walks each misconception's own exclusive-arc target out to its objective, resolving a
   * SUB_CONCEPT one hop further to its own parent CONCEPT -- at most two hops, matching the
   * ontology's own "no third nesting level" rule. A small, deliberate duplication of {@code
   * DiagnosticReportService.resolveAncestry}/{@code resolveTarget} (see class javadoc) -- batched, at
   * most two extra {@code diagnostic_node} reads and one {@code learning_objective} read regardless
   * of how many misconceptions are being resolved.
   */
  private AncestryResolution resolveAncestry(Collection<MisconceptionContextRow> misconceptions) {
    Set<UUID> directNodeIds = new HashSet<>();
    for (MisconceptionContextRow row : misconceptions) {
      if (row.targetDiagnosticNodeId() != null) {
        directNodeIds.add(row.targetDiagnosticNodeId());
      }
    }
    Map<UUID, DiagnosticNodeRow> nodeById = new HashMap<>(
        index(repository.findDiagnosticNodes(directNodeIds), DiagnosticNodeRow::id));

    Set<UUID> parentNodeIds = new HashSet<>();
    for (DiagnosticNodeRow node : nodeById.values()) {
      if (node.nodeType() == DiagnosticNodeType.SUB_CONCEPT && node.parentNodeId() != null) {
        parentNodeIds.add(node.parentNodeId());
      }
    }
    nodeById.putAll(index(repository.findDiagnosticNodes(parentNodeIds), DiagnosticNodeRow::id));

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
        index(repository.findObjectiveContext(objectiveIds), ObjectiveContextRow::objectiveId);
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

  private static Map<UUID, List<EvidenceObservationRow>> groupEvidence(List<EvidenceObservationRow> rows) {
    Map<UUID, List<EvidenceObservationRow>> byMisconceptionId = new HashMap<>();
    for (EvidenceObservationRow row : rows) {
      byMisconceptionId.computeIfAbsent(row.misconceptionId(), ignored -> new ArrayList<>()).add(row);
    }
    return byMisconceptionId;
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

  private record ResolvedTarget(
      MisconceptionTargetType targetType, UUID targetId, UUID objectiveId,
      ConceptContext concept, SubConceptContext subConcept) {
  }

  private record AncestryResolution(
      Map<UUID, ResolvedTarget> targetByMisconceptionId,
      Map<UUID, ObjectiveContextRow> objectiveContextById) {
  }
}
