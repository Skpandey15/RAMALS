package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.util.List;

/**
 * The bounded, deterministic, read-only projection of authored knowledge around one curriculum node
 * (M2-ADR-033 §5/§6). It answers "what prerequisite structure and authored misconception knowledge
 * exists around this objective / concept / sub-concept?" and nothing about any learner: it never
 * carries learner evidence, mastery, G2/G3 confidence, a probability that a learner holds a
 * misconception, a probe recommendation, or a root-cause claim (M2-ADR-033 §4, prompt §2/§15/§16).
 *
 * <p>Immutable. Every list is copied and unmodifiable, ordered by an explicit stable key (never
 * database row order), and its size is bounded by the single requested node's own authored content
 * -- there is no recursive traversal and no per-misconception fan-out (M2-ADR-033 §5, prompt §12).
 * A valid node with no authored misconceptions yields this view with empty {@code misconceptions} /
 * {@code relationships} / {@code prerequisiteLinks} (still populated {@code target} /
 * {@code prerequisites}); an unknown node is a {@link MisconceptionGraphTargetNotFoundException}
 * instead (prompt §14).
 *
 * @param target the resolved curriculum context of the requested node
 * @param prerequisites the owning skill's existing curriculum prerequisites, {@code stable_code}
 *     order
 * @param misconceptions the {@code PUBLISHED} misconceptions targeting the node via the exclusive
 *     arc, {@code (name, id)} order
 * @param relationships the {@code PUBLISHED} {@code MISCONCEPTION_RELATED} edges with at least one
 *     endpoint in {@code misconceptions}, {@code (type, a, b)} order
 * @param prerequisiteLinks the {@code PUBLISHED} {@code MISCONCEPTION_PREREQUISITE_LINK} edges whose
 *     misconception is in {@code misconceptions}, {@code (misconception, prerequisite code)} order
 */
public record MisconceptionGraphView(
    GraphTargetView target,
    List<CurriculumPrerequisiteView> prerequisites,
    List<PublishedMisconceptionView> misconceptions,
    List<PublishedMisconceptionRelationshipView> relationships,
    List<PublishedMisconceptionPrerequisiteLinkView> prerequisiteLinks) {

  public MisconceptionGraphView {
    prerequisites = List.copyOf(prerequisites);
    misconceptions = List.copyOf(misconceptions);
    relationships = List.copyOf(relationships);
    prerequisiteLinks = List.copyOf(prerequisiteLinks);
  }
}
