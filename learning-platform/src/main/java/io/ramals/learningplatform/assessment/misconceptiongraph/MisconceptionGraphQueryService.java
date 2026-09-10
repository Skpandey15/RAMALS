package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2-ADR-033 §5: the bounded, read-only, authored-knowledge-only misconception graph query surface.
 * Given one authoritative curriculum node it composes a deterministic {@link MisconceptionGraphView}
 * -- that node's curriculum context, its owning skill's existing prerequisites, the {@code
 * PUBLISHED} misconceptions targeting it, and the {@code PUBLISHED} {@code MISCONCEPTION_RELATED} /
 * {@code MISCONCEPTION_PREREQUISITE_LINK} edges among and from that set -- the same {@code
 * @Transactional(readOnly = true)} shape as the H6 / H7 read services.
 *
 * <p><b>Authored knowledge only (M2-ADR-033 §5, prompt §2/§15/§16).</b> This service composes no
 * learner state: it takes no learner id / interaction id / attempt id / evidence reference, calls
 * no mastery / evidence / G2 / G3 / H6 / H7 / diagnostic-confidence code, and returns nothing
 * per-learner. "Which learner holds misconception M, and how likely" is a genuinely different
 * capability that needs its own ADR reviewed against M2-ADR-028/029; it is not built here.
 *
 * <p><b>Not adaptive use (M2-ADR-033 §6, prompt §17).</b> Nothing here ranks misconceptions, scores
 * a probe, computes information gain or a posterior, or feeds {@code DIAGNOSTIC_SELECTION_V1}-
 * {@code V5} (or a future {@code V6}). The graph being queryable does not make it decide anything.
 *
 * <p><b>Bounded (M2-ADR-033 §5, prompt §12).</b> A call runs a fixed number of statements -- three
 * when the node has no published misconceptions, five otherwise -- never one per misconception.
 * There is no traversal method and no multi-hop expansion; a boundary {@code MISCONCEPTION_RELATED}
 * edge exposes its out-of-scope endpoint only as an id.
 */
@Service
public class MisconceptionGraphQueryService {

  private final MisconceptionGraphQueryRepository repository;

  public MisconceptionGraphQueryService(MisconceptionGraphQueryRepository repository) {
    this.repository = repository;
  }

  /**
   * The bounded authored graph projection for one curriculum node.
   *
   * @throws MisconceptionGraphTargetNotFoundException if no node of {@code target.kind()} has {@code
   *     target.id()} -- deterministically distinct from a valid node that simply has no authored
   *     misconception knowledge, which returns a successful empty-collection view
   */
  @Transactional(readOnly = true)
  public MisconceptionGraphView graphFor(MisconceptionGraphTarget target) {
    GraphTargetView targetView = repository.resolveTarget(target)     // Q1
        .orElseThrow(() -> new MisconceptionGraphTargetNotFoundException(target));

    List<CurriculumPrerequisiteView> prerequisites = repository.findCurriculumPrerequisites(  // Q2
        targetView.owningSkillId(), targetView.curriculumVersionId());

    List<PublishedMisconceptionView> misconceptions =                 // Q3
        repository.findPublishedMisconceptionsForTarget(target);

    if (misconceptions.isEmpty()) {
      // A valid node with no authored misconceptions: a successful empty graph, not an error
      // (prompt §14). Q4 and Q5 are skipped -- their id set would be empty.
      return new MisconceptionGraphView(
          targetView, prerequisites, List.of(), List.of(), List.of());
    }

    Set<UUID> misconceptionIds = misconceptions.stream()
        .map(PublishedMisconceptionView::id)
        .collect(Collectors.toCollection(LinkedHashSet::new));

    List<PublishedMisconceptionRelationshipView> relationships =      // Q4
        repository.findPublishedRelationshipsForMisconceptions(misconceptionIds);
    List<PublishedMisconceptionPrerequisiteLinkView> prerequisiteLinks =  // Q5
        repository.findPublishedPrerequisiteLinksForMisconceptions(misconceptionIds);

    return new MisconceptionGraphView(
        targetView, prerequisites, misconceptions, relationships, prerequisiteLinks);
  }
}
