package io.ramals.learningplatform.assessment.misconceptiongraph;

import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import java.util.UUID;

/**
 * One {@code PUBLISHED} {@code core.misconception} that targets the {@link MisconceptionGraphView}'s
 * requested node through M2-ADR-026's exclusive arc (M2-ADR-033 §5, §8). Immutable, authored
 * knowledge only.
 *
 * <p>Only misconceptions targeting <em>this exact node</em> are returned -- no ancestor or
 * descendant expansion (prompt §8). It carries the misconception's authored {@code name} and {@code
 * description} and its own target arc, and nothing learner-scoped: no evidence count, no confidence
 * band, no probability that any learner holds it (M2-ADR-033 §4).
 *
 * @param targetKind which level this misconception's exclusive arc points at
 * @param targetId the id of that objective or diagnostic node
 */
public record PublishedMisconceptionView(
    UUID id,
    String name,
    String description,
    MisconceptionTargetType targetKind,
    UUID targetId) {
}
