package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code PUBLISHED} {@code core.misconception_relationship} edge that the bounded surface
 * includes for a requested node: an edge with <b>at least one endpoint</b> in the returned
 * misconception set -- "the published {@code MISCONCEPTION_RELATED} edges among, and from, that set"
 * (M2-ADR-033 §5). Immutable, authored knowledge only.
 *
 * <p><b>Bounded, one hop, no expansion.</b> When exactly one endpoint is in scope this is a
 * boundary edge; {@link #sourceInScope()} / {@link #targetInScope()} say which. The out-of-scope
 * endpoint is exposed only as its id -- no external misconception name, description, or target is
 * fetched (the ADR authorizes returning the edges, not enlarging the misconception set; prompt
 * §9/§22). An external endpoint's own edges are never followed: this is not a graph-traversal API.
 * M2-ADR-033 §1 guarantees a published edge references only published endpoints, so a boundary edge
 * never discloses a {@code DRAFT} misconception.
 *
 * <p><b>Direction &amp; symmetry.</b> {@code sourceMisconceptionId} / {@code targetMisconceptionId}
 * are the stored {@code misconception_a_id} / {@code misconception_b_id}. For a symmetric type
 * ({@link #symmetric()} true: {@code CO_OCCURS_WITH}, {@code CONTRASTS_WITH}) that ordering is
 * canonical storage, not a semantic direction -- the relationship reads the same both ways. For the
 * directed {@code SPECIALISES}, {@code source -> target} is meaningful ("source is a more specific
 * case of target"); {@link #reverseReadingLabel()} is {@code "GENERALISES"} -- the label the same
 * one edge carries when read {@code target -> source}. {@code GENERALISES} is never a stored value
 * and never a second row (M2-ADR-033 §1/§23).
 *
 * <p>{@code rationale} is the authored explanatory text stored on the edge. It is authored prose
 * only: no confidence, probability, causal certainty, or learner diagnosis may be derived from it
 * (M2-ADR-033 §4, prompt §25).
 */
public record PublishedMisconceptionRelationshipView(
    UUID id,
    MisconceptionRelatedType relationshipType,
    UUID sourceMisconceptionId,
    UUID targetMisconceptionId,
    boolean sourceInScope,
    boolean targetInScope,
    boolean symmetric,
    String reverseReadingLabel,
    String rationale,
    Instant publishedAt) {
}
